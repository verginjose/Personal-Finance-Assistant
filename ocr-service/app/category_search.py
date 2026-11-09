"""Local cosine search over reviewed category examples.

The default sparse TF-IDF backend needs only the stdlib. The optional dense
backend loads a SentenceTransformer from a local directory, without downloads.
Scores are similarities, not calibrated probabilities.
"""
import collections
import hashlib
import json
import math
import os
import re


def features(text):
    words = re.findall(r"[^\W\d_]{2,}", text.lower(), re.UNICODE)
    # Do not let generic receipt boilerplate drive a category decision.
    stop = set("total subtotal tax date time receipt invoice cash card visa amount "
               "due paid thank you server table order item items qty price".split())
    return collections.Counter(w for w in words if w not in stop)


class CategorySearch:
    def __init__(self, path, allowed_categories, threshold=0.45, margin=0.12,
                 model_path=None):
        if not (0 <= threshold <= 1 and 0 <= margin <= 1):
            raise ValueError("Vector threshold and margin must be between 0 and 1")
        with open(path, "rb") as stream:
            raw = stream.read()
        self.fingerprint = hashlib.sha256(raw).hexdigest()
        self.examples = json.loads(raw)
        if not isinstance(self.examples, list) or not self.examples:
            raise ValueError("Category examples must be a nonempty JSON list")
        seen = set()
        for row in self.examples:
            if (not isinstance(row, dict) or row.get("category") not in allowed_categories
                    or not isinstance(row.get("text"), str) or not features(row["text"])
                    or not isinstance(row.get("id"), str) or not row["id"]):
                raise ValueError("Each example needs a unique id, valid category and text")
            if row["id"] in seen:
                raise ValueError("Duplicate example id: " + row["id"])
            seen.add(row["id"])
        self.threshold, self.margin = threshold, margin
        self.model = None
        if model_path:
            if not os.path.isdir(model_path):
                raise ValueError("--category-model must be an existing local model directory")
            from sentence_transformers import SentenceTransformer
            self.model = SentenceTransformer(model_path, local_files_only=True,
                                             trust_remote_code=False, device="cpu")
            self.backend = "sentence-transformers"
            self.vectors = self.model.encode(
                [r["text"] for r in self.examples], normalize_embeddings=True)
        else:
            self.backend = "tfidf"
            counts = [features(r["text"]) for r in self.examples]
            df = collections.Counter(k for row in counts for k in row)
            self.idf = {k: math.log((1 + len(counts)) / (1 + n)) + 1
                        for k, n in df.items()}
            self.vectors = [self._vector(c) for c in counts]

    def _vector(self, counts):
        # Unknown query words count in the norm, reducing spurious matches
        # based on a single familiar word in otherwise unrelated text.
        vector = {k: (1 + math.log(n)) * self.idf.get(
            k, math.log(1 + len(self.examples)) + 1) for k, n in counts.items()}
        norm = math.sqrt(sum(v * v for v in vector.values()))
        return {k: v / norm for k, v in vector.items()} if norm else {}

    def search(self, text, exclude_id=None):
        if self.model is not None:
            query = self.model.encode([text], normalize_embeddings=True)[0]
            scores = [float(v @ query) for v in self.vectors]
        else:
            query = self._vector(features(text))
            scores = [sum(query.get(k, 0) * v for k, v in row.items())
                      for row in self.vectors]
        ranked = sorted(((score, row) for score, row in zip(scores, self.examples)
                         if row["id"] != exclude_id), key=lambda p: (-p[0], p[1]["id"]))
        evidence = {"backend": self.backend, "index_sha256": self.fingerprint,
                    "threshold": self.threshold, "required_margin": self.margin,
                    "neighbors": [{"id": r["id"], "category": r["category"],
                                   "score": round(s, 4)} for s, r in ranked[:3]]}
        if not ranked:
            return None, dict(evidence, accepted=False)
        best, row = ranked[0]
        runner = max((s for s, r in ranked if r["category"] != row["category"]), default=0)
        accepted = best >= self.threshold and best - runner >= self.margin
        evidence.update(accepted=accepted, score=round(best, 4),
                        margin=round(best - runner, 4))
        return row["category"] if accepted else None, evidence

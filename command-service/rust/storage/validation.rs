//! Filename sanitization, content types and file signature validation.

pub fn safe_filename(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("upload");
    let safe: String = base
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || "._-".contains(c) {
                c
            } else {
                '_'
            }
        })
        .collect();
    if safe.is_empty() || safe == "." || safe == ".." {
        "upload".into()
    } else {
        safe
    }
}
pub fn content_type(name: &str) -> &'static str {
    match name
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "pdf" => "application/pdf",
        "csv" => "text/csv; charset=utf-8",
        _ => "application/octet-stream",
    }
}
pub fn valid_magic(head: &[u8], kind: &str) -> bool {
    match kind {
        "image/png" => head.starts_with(b"\x89PNG\r\n\x1a\n"),
        "image/jpeg" => head.starts_with(b"\xff\xd8\xff"),
        "image/gif" => head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a"),
        "image/webp" => head.starts_with(b"RIFF") && head.get(8..12) == Some(b"WEBP"),
        "application/pdf" => head.starts_with(b"%PDF"),
        "text/csv; charset=utf-8" => !head.is_empty() && !head.contains(&0),
        _ => false,
    }
}

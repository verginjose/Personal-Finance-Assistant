"""Compatibility import for the production category-search module."""
import importlib.util
from pathlib import Path
_spec = importlib.util.spec_from_file_location("production_category_search", Path(__file__).resolve().parents[1] / "app/category_search.py")
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
CategorySearch = _module.CategorySearch
features = _module.features

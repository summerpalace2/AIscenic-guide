from datetime import datetime
from typing import Optional
from pydantic import BaseModel

class KnowledgeCreate(BaseModel):
    title: str
    category: str
    content: Optional[str] = None
    tags: Optional[list[str]] = None

class KnowledgeUpdate(BaseModel):
    title: Optional[str] = None
    category: Optional[str] = None
    content: Optional[str] = None
    tags: Optional[list[str]] = None

class KnowledgeItem(BaseModel):
    id: str
    title: str
    category: str
    content_snippet: str
    status: str
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

class KnowledgeDetail(BaseModel):
    id: str
    title: str
    category: str
    content: str
    file_url: str = ""
    status: str
    vector_status: str
    tags: list[str] = []
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

class CategoryItem(BaseModel):
    key: str
    label: str
    count: int

class SyncResponse(BaseModel):
    doc_id: str
    vector_status: str
    chunk_count: int

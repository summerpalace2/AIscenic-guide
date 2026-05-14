from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile, File, Form
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.schemas.knowledge import KnowledgeCreate, KnowledgeUpdate
from app.services.knowledge_service import knowledge_service
from app.services.file_storage import file_storage
from app.api.deps import get_admin_user
from app.models.user import User

router = APIRouter(prefix='/knowledge', tags=['Knowledge'])

@router.get('')
async def list_knowledge(category: Optional[str] = None, keyword: Optional[str] = None, page: int = Query(1), size: int = Query(20), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': knowledge_service.get_list(db, category, keyword, page, size)}

@router.get('/categories')
async def get_categories(admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': knowledge_service.get_categories(db)}

@router.get('/{doc_id}')
async def get_document(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        doc = knowledge_service.get_detail(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'OK', 'data': {'id': str(doc.id), 'title': doc.title, 'category': doc.category, 'content': doc.content, 'file_url': doc.file_url, 'status': doc.status, 'vector_status': doc.vector_status, 'tags': doc.tags or [], 'created_at': doc.created_at, 'updated_at': doc.updated_at}}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.post('', status_code=201)
async def create_document(req: KnowledgeCreate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    doc = knowledge_service.create(db, req, str(admin.id))
    return {'code': 0, 'success': True, 'message': 'Created', 'data': {'id': str(doc.id)}}

@router.post('/upload', status_code=201)
async def upload_document(
    file: UploadFile = File(...),
    title: str = Form(...),
    category: str = Form(...),
    content: Optional[str] = Form(None),
    tags: Optional[str] = Form(None),
    admin: User = Depends(get_admin_user),
    db: Session = Depends(get_db),
):
    """上传文件并创建知识文档"""
    try:
        # 保存文件到 knowledge 子目录
        file_url = await file_storage.save(file, sub_dir='knowledge')
    except ValueError as e:
        raise HTTPException(status_code=400, detail={'code': 40001, 'success': False, 'message': str(e)})

    # 解析 tags（逗号分隔的字符串转为列表）
    tag_list = [t.strip() for t in tags.split(',') if t.strip()] if tags else []

    # 创建知识文档记录
    req = KnowledgeCreate(title=title, category=category, content=content or '', tags=tag_list, file_url=file_url)
    doc = knowledge_service.create(db, req, str(admin.id))
    return {'code': 0, 'success': True, 'message': 'Created', 'data': {'id': str(doc.id), 'file_url': file_url}}

@router.put('/{doc_id}')
async def update_document(doc_id: str, req: KnowledgeUpdate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        knowledge_service.update(db, doc_id, req)
        return {'code': 0, 'success': True, 'message': 'Updated'}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.delete('/{doc_id}')
async def delete_document(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        knowledge_service.delete(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'Deleted', 'data': None}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.post('/{doc_id}/sync')
async def trigger_sync(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        data = knowledge_service.trigger_sync(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'Sync triggered', 'data': data}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

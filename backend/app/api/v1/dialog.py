from fastapi import APIRouter, Depends, Query, UploadFile, File, Form
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.schemas.dialog import MessageRequest
from app.services.dialog_service import dialog_service
from app.api.deps import get_current_user
from app.middleware.sensitive_words import check_sensitive_words
from app.models.user import User

router = APIRouter(prefix='/dialog', tags=['Dialog'])

@router.post('/message')
async def send_message(req: MessageRequest, user: Optional[User] = Depends(get_current_user), db: Session = Depends(get_db), _=Depends(check_sensitive_words)):
    data = await dialog_service.send_message(db=db, content=req.content, session_id=req.session_id, user_id=str(user.id) if user else None, scenic_spot_id=req.scenic_spot_id)
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

@router.get('/sessions')
async def list_sessions(page: int = Query(1), size: int = Query(20), user: Optional[User] = Depends(get_current_user), db: Session = Depends(get_db)):
    data = dialog_service.get_sessions(db, user_id=str(user.id) if user else None, page=page, size=size)
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

@router.get('/sessions/{session_id}/messages')
async def get_messages(session_id: str, before_id: Optional[str] = None, limit: int = Query(50), db: Session = Depends(get_db)):
    data = dialog_service.get_messages(db, session_id, before_id, limit)
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

@router.delete('/sessions/{session_id}')
async def delete_session(session_id: str, db: Session = Depends(get_db)):
    dialog_service.delete_session(db, session_id)
    return {'code': 0, 'success': True, 'message': 'Deleted', 'data': None}

@router.post('/voice')
async def send_voice(
    audio: UploadFile = File(...),
    session_id: Optional[str] = Form(None),
    user: Optional[User] = Depends(get_current_user),
    db: Session = Depends(get_db),
    _=Depends(check_sensitive_words),
):
    """发送语音消息（Mock：固定文本模拟 ASR 识别结果）"""
    mock_text = '用户语音消息的模拟转写文本'
    data = await dialog_service.send_message(
        db=db, content=mock_text, session_id=session_id, user_id=str(user.id) if user else None
    )
    return {'code': 0, 'success': True, 'message': 'OK', 'data': data}

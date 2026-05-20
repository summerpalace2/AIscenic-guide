from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from pydantic import BaseModel
from app.db.session import get_db
from app.schemas.user import RegisterRequest, LoginRequest, AdminLoginRequest
from app.services.auth_service import auth_service
from app.api.deps import get_current_user
from app.models.user import User

router = APIRouter(prefix='/auth', tags=['Auth'])

@router.post('/register', status_code=status.HTTP_201_CREATED)
async def register(req: RegisterRequest, db: Session = Depends(get_db)):
    try:
        return {'code': 0, 'success': True, 'message': 'Registered', 'data': auth_service.register(db, req)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail={'code': 40003, 'success': False, 'message': str(e)})

@router.post('/login')
async def login(req: LoginRequest, db: Session = Depends(get_db)):
    try:
        return {'code': 0, 'success': True, 'message': 'Logged in', 'data': auth_service.login(db, req)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail={'code': 40001, 'success': False, 'message': str(e)})

@router.post('/admin/login')
async def admin_login(req: AdminLoginRequest, db: Session = Depends(get_db)):
    try:
        return {'code': 0, 'success': True, 'message': 'Admin logged in', 'data': auth_service.admin_login(db, req.username, req.password)}
    except ValueError as e:
        raise HTTPException(status_code=400, detail={'code': 40001, 'success': False, 'message': str(e)})

@router.post('/wechat')
async def wechat_login(code: str = Query(...)):
    return {'code': 0, 'success': True, 'message': 'WeChat login stub', 'data': {'user_id': '', 'token': ''}}

class TokenRefreshRequest(BaseModel):
    refresh_token: str

@router.post('/refresh')
async def refresh_token(req: TokenRefreshRequest):
    from app.core.security import decode_token, create_access_token
    payload = decode_token(req.refresh_token)
    if not payload:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail={'code': 40102, 'success': False, 'message': 'Invalid refresh token'})
    token = create_access_token({'sub': payload.get('sub', ''), 'role': payload.get('role', 'tourist')})
    return {'code': 0, 'success': True, 'message': 'Token refreshed', 'data': {'token': token, 'expires_in': 3600}}

@router.get('/me')
async def get_me(user: User = Depends(get_current_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'user_id': str(user.id), 'nickname': user.nickname, 'avatar': user.avatar, 'role': user.role, 'phone': user.phone or '', 'interests': user.interests or [], 'created_at': user.created_at}}

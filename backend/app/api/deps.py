import uuid

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.core.security import decode_token
from app.models.user import User

security = HTTPBearer()

async def get_current_user(credentials=Depends(security), db: Session = Depends(get_db)) -> User:
    payload = decode_token(credentials.credentials)
    if not payload:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail={'code': 40101, 'success': False, 'message': 'Token invalid or expired'})
    # 将 JWT 中的字符串 sub 转换为 uuid.UUID，兼容 MySQL Uuid 列类型
    user = db.query(User).filter(User.id == uuid.UUID(payload.get('sub'))).first()
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    return user

async def get_admin_user(user: User = Depends(get_current_user)) -> User:
    if user.role not in ('admin', 'super_admin'):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={'code': 40301, 'success': False, 'message': 'Admin access required'})
    return user

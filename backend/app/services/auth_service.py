import uuid
from sqlalchemy.orm import Session
from app.models.user import User
from app.schemas.user import RegisterRequest, LoginRequest
from app.core.security import get_password_hash, verify_password, create_access_token, create_refresh_token

class AuthService:
    def register(self, db: Session, req: RegisterRequest) -> dict:
        if db.query(User).filter(User.phone == req.phone).first():
            raise ValueError('Phone already registered')
        user = User(id=uuid.uuid4(), phone=req.phone,
                    password_hash=get_password_hash(req.password),
                    nickname=req.nickname or f'Tourist{req.phone[-4:]}', role='tourist')
        db.add(user)
        db.commit()
        db.refresh(user)
        return {'user_id': str(user.id), 'token': create_access_token({'sub': str(user.id), 'role': user.role}), 'expires_in': 3600}

    def login(self, db: Session, req: LoginRequest) -> dict:
        user = db.query(User).filter(User.phone == req.phone).first()
        if not user or not verify_password(req.password, user.password_hash):
            raise ValueError('Invalid phone or password')
        return {'user_id': str(user.id), 'token': create_access_token({'sub': str(user.id), 'role': user.role}), 'expires_in': 3600}

    def admin_login(self, db: Session, username: str, password: str) -> dict:
        user = db.query(User).filter(User.nickname == username, User.role.in_(['admin', 'super_admin'])).first()
        if not user or not verify_password(password, user.password_hash):
            raise ValueError('Invalid credentials')
        return {'user_id': str(user.id), 'token': create_access_token({'sub': str(user.id), 'role': user.role}),
                'refresh_token': create_refresh_token({'sub': str(user.id)}), 'expires_in': 3600, 'role': user.role}

auth_service = AuthService()

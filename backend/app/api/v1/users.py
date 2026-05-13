from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.models.user import User
from app.schemas.user import UpdateInterestsRequest, UpdateProfileRequest
from app.api.deps import get_current_user

router = APIRouter(prefix='/users', tags=['Users'])

@router.get('/{user_id}')
async def get_user(user_id: str, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'user_id': str(user.id), 'nickname': user.nickname, 'avatar': user.avatar, 'role': user.role, 'phone': user.phone or '', 'interests': user.interests or [], 'created_at': user.created_at}}

@router.put('/{user_id}/interests')
async def update_interests(user_id: str, req: UpdateInterestsRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    user.interests = req.interests; db.commit()
    return {'code': 0, 'success': True, 'message': 'Updated', 'data': {'user_id': str(user.id), 'interests': user.interests}}

@router.put('/{user_id}/profile')
async def update_profile(user_id: str, req: UpdateProfileRequest, db: Session = Depends(get_db), current_user: User = Depends(get_current_user)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    for f, v in req.model_dump(exclude_unset=True).items(): setattr(user, f, v)
    db.commit()
    return {'code': 0, 'success': True, 'message': 'Updated'}

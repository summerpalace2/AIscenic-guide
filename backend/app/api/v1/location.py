import math
from fastapi import APIRouter, Depends, HTTPException, Query
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.models.scenic_spot import ScenicSpot

router = APIRouter(prefix='/location', tags=['Location'])


def _haversine(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    """计算两个GPS坐标之间的球面距离（米），使用Haversine公式"""
    R = 6371000  # 地球平均半径（米）
    dlon = math.radians(lon2 - lon1)
    dlat = math.radians(lat2 - lat1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

@router.get('/scenic-spots')
async def list_spots(category: Optional[str] = None, keyword: Optional[str] = None, db: Session = Depends(get_db)):
    q = db.query(ScenicSpot)
    if category: q = q.filter(ScenicSpot.category == category)
    if keyword: q = q.filter(ScenicSpot.name.ilike(f'%{keyword}%'))
    spots = q.all()
    items = [{'id': str(s.id), 'name': s.name, 'category': s.category, 'description': s.description, 'longitude': s.longitude, 'latitude': s.latitude, 'audio_url': s.audio_url, 'tags': s.tags or []} for s in spots]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'total': len(items), 'items': items}}

@router.get('/scenic-spots/{spot_id}')
async def get_spot(spot_id: str, db: Session = Depends(get_db)):
    s = db.query(ScenicSpot).filter(ScenicSpot.id == spot_id).first()
    if not s: raise HTTPException(status_code=404, detail={'code': 40404, 'success': False, 'message': 'Spot not found'})
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'id': str(s.id), 'name': s.name, 'category': s.category, 'description': s.description, 'longitude': s.longitude, 'latitude': s.latitude, 'audio_url': s.audio_url, 'tags': s.tags or []}}

@router.get('/nearby')
async def nearby_spots(longitude: float = Query(...), latitude: float = Query(...), radius: int = Query(500), db: Session = Depends(get_db)):
    """根据GPS坐标和半径（米）查询附近的景点，按距离升序排列"""
    spots = db.query(ScenicSpot).all()
    items = []
    for s in spots:
        dist = _haversine(longitude, latitude, s.longitude, s.latitude)
        if dist <= radius:
            items.append({
                'id': str(s.id), 'name': s.name, 'category': s.category,
                'description': s.description,
                'longitude': s.longitude, 'latitude': s.latitude,
                'distance': round(dist, 1)
            })
    items.sort(key=lambda x: x['distance'])
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'total': len(items), 'items': items}}

"""
景点种子数据脚本
运行方式：python -m scripts.seed_spots
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
import uuid
from datetime import datetime, timezone
from app.db.session import SessionLocal
from app.db.base import Base
from app.models.scenic_spot import ScenicSpot

SEED_DATA = [
    {'name': '太和殿', 'category': 'history', 'description': '故宫最大的宫殿，始建于明永乐年间，是皇帝举行大典的地方。', 'longitude': 116.397, 'latitude': 39.916, 'tags': ['必看', '明代建筑', '皇家']},
    {'name': '乾清宫', 'category': 'history', 'description': '明清两代皇帝的寝宫，也是处理日常政务的地方。', 'longitude': 116.397, 'latitude': 39.917, 'tags': ['明代建筑', '皇家']},
    {'name': '坤宁宫', 'category': 'history', 'description': '明清两代皇后的寝宫，清代改为祭神场所。', 'longitude': 116.397, 'latitude': 39.918, 'tags': ['皇家']},
    {'name': '御花园', 'category': 'nature', 'description': '故宫内的皇家园林，古柏参天，亭台楼阁错落有致。', 'longitude': 116.396, 'latitude': 39.919, 'tags': ['园林', '休闲']},
    {'name': '珍宝馆', 'category': 'facility', 'description': '展示故宫珍藏的各类奇珍异宝，包括金银器、玉器、瓷器等。', 'longitude': 116.398, 'latitude': 39.915, 'tags': ['展览', '必看']},
    {'name': '钟表馆', 'category': 'facility', 'description': '收藏了18-19世纪中外制造的各式钟表，精美绝伦。', 'longitude': 116.398, 'latitude': 39.914, 'tags': ['展览']},
    {'name': '文华殿', 'category': 'history', 'description': '明代为太子讲学之所，清代改为举行经筵的地方。', 'longitude': 116.395, 'latitude': 39.913, 'tags': ['明代建筑']},
    {'name': '武英殿', 'category': 'history', 'description': '明代皇帝斋居和召见大臣的地方，清代改为修书处。', 'longitude': 116.394, 'latitude': 39.912, 'tags': ['明代建筑']},
    {'name': '神武门', 'category': 'history', 'description': '故宫北门，明代称玄武门，清代改为神武门。', 'longitude': 116.397, 'latitude': 39.921, 'tags': ['城门', '明代建筑']},
    {'name': '角楼', 'category': 'history', 'description': '故宫四角的角楼，造型精美，是故宫标志性建筑之一。', 'longitude': 116.399, 'latitude': 39.920, 'tags': ['必看', '标志性建筑']},
]

def seed():
    db = SessionLocal()
    try:
        existing = db.query(ScenicSpot).count()
        if existing > 0:
            print(f'数据库已有 {existing} 条景点数据，跳过种子导入')
            return
        for data in SEED_DATA:
            spot = ScenicSpot(id=uuid.uuid4(), **data)
            db.add(spot)
        db.commit()
        print(f'成功插入 {len(SEED_DATA)} 条景点种子数据')
    finally:
        db.close()

if __name__ == '__main__':
    seed()

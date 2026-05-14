"""
文件存储服务
支持本地文件存储和 MinIO 两种后端
"""
import os
import uuid
from pathlib import Path
from fastapi import UploadFile
from app.core.config import get_settings

settings = get_settings()

# 允许上传的文件类型
ALLOWED_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp',
                      '.mp3', '.wav', '.ogg', '.aac',
                      '.pdf', '.doc', '.docx', '.xls', '.xlsx',
                      '.txt', '.md', '.csv'}

# 最大文件大小（50MB）
MAX_FILE_SIZE = 50 * 1024 * 1024


class LocalFileStorage:
    """本地文件存储实现"""

    def __init__(self, base_dir: str = None):
        self.base_dir = Path(base_dir or os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(__file__))), 'uploads'
        ))
        self.base_dir.mkdir(parents=True, exist_ok=True)

    async def save(self, file: UploadFile, sub_dir: str = '') -> str:
        """保存上传文件，返回相对路径"""
        # 验证文件类型
        ext = Path(file.filename or '').suffix.lower()
        if ext not in ALLOWED_EXTENSIONS:
            raise ValueError(f'不支持的文件类型: {ext}')

        # 读取文件内容
        content = await file.read()
        if len(content) > MAX_FILE_SIZE:
            raise ValueError(f'文件大小超过限制（最大50MB）')

        # 生成存储路径
        target_dir = self.base_dir / sub_dir
        target_dir.mkdir(parents=True, exist_ok=True)
        filename = f'{uuid.uuid4().hex}{ext}'
        filepath = target_dir / filename

        # 写入文件
        with open(filepath, 'wb') as f:
            f.write(content)

        return f'/{sub_dir}/{filename}' if sub_dir else f'/{filename}'

    def get_path(self, relative_path: str) -> str:
        """获取文件的绝对路径"""
        return str(self.base_dir / relative_path.lstrip('/'))

    def delete(self, relative_path: str):
        """删除文件"""
        filepath = self.base_dir / relative_path.lstrip('/')
        if filepath.exists():
            filepath.unlink()


# 默认使用本地存储
file_storage = LocalFileStorage()

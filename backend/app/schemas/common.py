from typing import Any
from pydantic import BaseModel

class ApiResponse(BaseModel):
    code: int = 0
    success: bool = True
    message: str = "success"
    data: Any = None

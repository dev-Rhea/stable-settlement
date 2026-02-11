FROM python:3.12-slim

RUN adduser --disabled-password --no-create-home appuser

WORKDIR /app

COPY . .
RUN pip install --no-cache-dir .

USER appuser

CMD ["python", "-m", "src.main"]

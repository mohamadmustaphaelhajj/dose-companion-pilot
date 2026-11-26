FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home pilot
COPY bridge.py hosted.py gunicorn.conf.py ./
USER pilot
EXPOSE 8080
CMD ["gunicorn", "--config", "gunicorn.conf.py", "hosted:application"]

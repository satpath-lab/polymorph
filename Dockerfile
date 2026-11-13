FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends lua5.4 php-cli nodejs ruby tini \
 && ln -sf /usr/bin/lua5.4 /usr/local/bin/lua && rm -rf /var/lib/apt/lists/* \
 && useradd -m -u 10001 poly
WORKDIR /app
COPY --chown=poly:poly . .
RUN mkdir -p /data && chown poly:poly /data
USER poly
ENV POLY_DATA=/data PORT=8787 POLY_WORKERS=4 PYTHONUNBUFFERED=1
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=15s --timeout=3s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8787/healthz')"
ENTRYPOINT ["tini", "--"]
CMD ["python", "poly.py", "serve"]

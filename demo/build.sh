#!/bin/bash
# 构建三个 demo 服务的 Docker 镜像,  推送到 registry.cn-hangzhou.aliyuncs.com/sxxpqp
# 用法: bash build.sh

set -e
REGISTRY=registry.cn-hangzhou.aliyuncs.com/sxxpqp
cd "$(dirname "$0")"

echo "=== Building go-service ==="
docker build -t $REGISTRY/go-service:latest go-service/
docker push $REGISTRY/go-service:latest

echo "=== Building java-service ==="
docker build -t $REGISTRY/java-service:latest java-service/
docker push $REGISTRY/java-service:latest

echo "=== Building python-service ==="
docker build -t $REGISTRY/python-service:latest python-service/
docker push $REGISTRY/python-service:latest

echo "=== Done ==="
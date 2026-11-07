#!/usr/bin/env python3
"""
Load generator — 持续调用 demo 服务链, 产生 traces / logs / metrics。

用法:
  1. port-forward Go 服务:
     kubectl port-forward -n demo svc/go-service 8080:8080
  2. python loadgen.py

  或集群内运行:
     kubectl run loadgen --rm -it --image=python:3.12-alpine --restart=Never -n demo -- \
       python -c "$(cat loadgen.py)"
"""
import time
import random
import urllib.request
import sys

GO_URL = "http://go-service.demo:8080"

while True:
    try:
        # 普通调用
        req = urllib.request.Request(GO_URL + "/api/hello")
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"[hello] {resp.read().decode()}")

        time.sleep(random.uniform(0.5, 2))

        # 链式调用 Go → Java → Python
        req = urllib.request.Request(GO_URL + "/api/chain")
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(f"[chain] {resp.read().decode()}")

        time.sleep(random.uniform(1, 3))
    except Exception as e:
        print(f"[error] {e}", file=sys.stderr)
        time.sleep(5)
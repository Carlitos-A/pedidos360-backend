#!/usr/bin/env python3
"""Verify the deployed SPA API contract with real Entra access tokens, without logging them."""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
import urllib.parse
from pathlib import Path


def claims(token):
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("El archivo no contiene un JWT de tres partes")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def request(base, path, token=None, method="GET", body=None, headers=None):
    request_headers = dict(headers or {})
    if token is not None:
        request_headers["Authorization"] = "Bearer " + token
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def json_body(raw):
    return json.loads(raw.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="El mismo apiBaseUrl de Angular, sin /api")
    parser.add_argument("--admin-token-file", required=True, type=Path)
    parser.add_argument("--client-token-file", required=True, type=Path)
    parser.add_argument("--spa-origin", help="Origen real de Angular para comprobar CORS")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    if not base.startswith("https://") and not base.startswith("http://localhost"):
        parser.error("El gateway remoto debe usar HTTPS")
    admin = args.admin_token_file.read_text(encoding="utf-8").strip()
    client = args.client_token_file.read_text(encoding="utf-8").strip()
    admin_claims, client_claims = claims(admin), claims(client)
    if "Admin" not in admin_claims.get("roles", []):
        parser.error("El access token Admin no trae el rol Admin")
    if "Cliente" not in client_claims.get("roles", []):
        parser.error("El access token Cliente no trae el rol Cliente")
    if admin_claims.get("aud") != client_claims.get("aud") or not admin_claims.get("aud"):
        parser.error("Los access tokens deben tener la misma audiencia de API")
    if any("pedidos.read" not in token_claims.get("scp", "").split()
           for token_claims in (admin_claims, client_claims)):
        parser.error("Ambos access tokens deben incluir el scope pedidos.read")
    oid, tid = client_claims.get("oid"), client_claims.get("tid")
    if not oid or not tid:
        parser.error("El access token Cliente debe traer oid y tid")
    for label, token_claims in (("Admin", admin_claims), ("Cliente", client_claims)):
        if token_claims.get("exp", 0) <= time.time():
            parser.error("El access token %s ya expiró" % label)
    results = []

    def check(name, expected, path, token=None, method="GET", body=None, headers=None):
        try:
            status, response_headers, raw = request(base, path, token, method, body, headers)
            passed = status in expected
            results.append({"check": name, "result": "PASS" if passed else "FAIL", "http": status,
                            "expected": sorted(expected)})
            return json_body(raw) if passed and raw else None, response_headers
        except (OSError, ValueError, json.JSONDecodeError) as error:
            results.append({"check": name, "result": "FAIL", "error": str(error)})
            return None, {}

    check("sin token", {401}, "/api/orders?page=0&pageSize=1")
    signature = admin.split(".")[2]
    altered = ("A" if signature[0] != "A" else "B") + signature[1:]
    check("firma alterada", {401}, "/api/orders?page=0&pageSize=1",
          ".".join(admin.split(".")[:2] + [altered]))

    admin_orders, _ = check("Admin pedidos", {200}, "/api/orders?page=0&pageSize=100", admin)
    registered, _ = check("Cliente registro propio", {200}, "/api/customers/me", client, "POST")
    if registered and (registered.get("id") != oid or registered.get("tenantId") != tid):
        results.append({"check": "identidad registrada desde JWT", "result": "FAIL"})
    check("Admin no puede registrarse como Cliente", {403}, "/api/customers/me", admin, "POST")
    search = (registered or {}).get("name") or client_claims.get("name") or \
        client_claims.get("preferred_username") or oid
    if len(search.strip()) < 2:
        search = oid
    found, _ = check("Admin busca clientes registrados", {200},
                     "/api/customers?q=" + urllib.parse.quote(search[:40]), admin)
    if isinstance(found, list) and not any(item.get("id") == oid for item in found):
        results.append({"check": "Cliente aparece en directorio", "result": "FAIL"})
    for path in ("/api/products?page=0&pageSize=20", "/api/reports/kpis?days=30",
                 "/api/reports/sales?days=365", "/api/reports/lead-time?days=365",
                 "/api/reports/top-products?days=365", "/api/audit/events?page=0&pageSize=20",
                 "/api/health"):
        check("Admin " + path, {200}, path, admin)
    if "pedidos.write" in admin_claims.get("scp", "").split():
        check("Admin escritura enrutada y validada", {400}, "/api/orders", admin, "POST", b"{}")
    else:
        results.append({"check": "Admin escritura enrutada y validada", "result": "SKIP",
                        "reason": "El token Admin no incluye pedidos.write"})

    check("Cliente productos", {200}, "/api/products?page=0&pageSize=20", client)
    for path in ("/api/reports/kpis?days=30", "/api/audit/events?page=0&pageSize=20", "/api/health"):
        check("Cliente denegado " + path, {403}, path, client)
    check("Cliente creación denegada", {403}, "/api/orders", client, "POST", b"{}")
    check("Cliente catálogo denegado", {403}, "/api/products", client, "POST", b"{}")

    page, total, seen = 0, None, 0
    while page < 100:
        listing, _ = check("Cliente pedidos página %d" % page, {200},
                           "/api/orders?page=%d&pageSize=100" % page, client)
        if listing is None:
            break
        if not isinstance(listing, dict) or not isinstance(listing.get("items"), list):
            results.append({"check": "contrato de paginación", "result": "FAIL"})
            break
        if total is None:
            total = listing.get("total")
        items = listing["items"]
        if any(item.get("customerId") != oid or item.get("customerTenantId") != tid for item in items):
            results.append({"check": "aislamiento de Cliente", "result": "FAIL"})
        else:
            results.append({"check": "aislamiento de Cliente página %d" % page, "result": "PASS"})
        seen += len(items)
        if not items or total is None or seen >= total:
            break
        page += 1
    if total is not None and seen != total:
        results.append({"check": "total no filtrado o paginación incompleta", "result": "FAIL",
                        "total": total, "items": seen})

    foreign = next((item for item in (admin_orders or {}).get("items", [])
                    if item.get("customerId") != oid or item.get("customerTenantId") != tid), None)
    if foreign and foreign.get("id"):
        check("detalle ajeno denegado", {403, 404}, "/api/orders/" + str(foreign["id"]), client)
    else:
        results.append({"check": "detalle ajeno denegado", "result": "SKIP",
                        "reason": "Se requiere un pedido de otro propietario en la primera página Admin"})

    if args.spa_origin:
        _, cors = check("preflight PATCH", {200, 204}, "/api/orders/1/status", method="OPTIONS",
                        headers={"Origin": args.spa_origin,
                                 "Access-Control-Request-Method": "PATCH",
                                 "Access-Control-Request-Headers": "authorization,content-type"})
        if cors.get("Access-Control-Allow-Origin") != args.spa_origin:
            results.append({"check": "origen SPA permitido", "result": "FAIL"})
        else:
            results.append({"check": "origen SPA permitido", "result": "PASS"})

    print(json.dumps({"baseUrl": base, "results": results}, ensure_ascii=False, indent=2))
    return 1 if any(item["result"] == "FAIL" for item in results) else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""商品搜索真实链路验收；仅在显式指定的隔离测试环境写入自有商品。"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def required(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit('必须显式设置 ' + name)
    return value.rstrip('/')


if os.environ.get('E2E_WRITE') != '1':
    raise SystemExit('本脚本会创建和下架测试商品，必须显式设置 E2E_WRITE=1')
PRODUCT = required('TEST_PRODUCT_URL')
SEARCH = required('TEST_SEARCH_URL')
TOKEN = required('TEST_ADMIN_TOKEN')
OTHER_TOKEN = required('TEST_NONADMIN_TOKEN')
for url in (PRODUCT, SEARCH):
    if urllib.parse.urlparse(url).scheme not in ('http', 'https'):
        raise SystemExit('测试 URL 必须为 http 或 https')


def request(method, url, body=None, token=None, status=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            code, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        code, raw = error.code, error.read()
    assert code == status, (method, url, code, raw.decode())
    return json.loads(raw) if raw else {}


category = 'e2e_' + uuid.uuid4().hex[:12]
product_id = category + '_product'
admin_url = PRODUCT + '/admin/products/' + product_id
search_url = SEARCH + '/products?categoryId=' + category
body = dict(productId=product_id, title='华为智能手机', categoryId=category, brandId='e2e_brand', priceCents=199900, status='ON_SALE')


def await_items(predicate):
    deadline = time.monotonic() + 45
    last = None
    while time.monotonic() < deadline:
        last = request('GET', search_url)['data']
        if predicate(last):
            return last
        time.sleep(0.5)
    raise AssertionError(('异步投影超时', last))


created = False
version = None
try:
    request('POST', PRODUCT + '/admin/products', body, status=401)
    request('POST', PRODUCT + '/admin/products', body, OTHER_TOKEN, status=403)
    result = request('POST', PRODUCT + '/admin/products', body, TOKEN, 201)['data']
    created, version = True, result['version']
    await_items(lambda page: page['total'] == 1 and page['items'][0]['priceCents'] == 199900)
    keyword = urllib.parse.quote('智能手机')
    assert request('GET', search_url + '&q=' + keyword)['data']['total'] == 1
    assert request('GET', search_url + '&minPriceCents=200000')['data']['total'] == 0
    request('GET', search_url + '&size=101', status=400)
    print('通过：权限拒绝、创建→Outbox/MQ→ES、中文搜索、金额筛选、非法分页')
    update = {k: v for k, v in body.items() if k != 'productId'}
    update.update(title='华为智能平板', priceCents=299900, expectedVersion=version)
    version = request('PUT', admin_url, update, TOKEN)['data']['version']
    await_items(lambda page: page['total'] == 1 and page['items'][0]['priceCents'] == 299900 and page['items'][0]['title'] == '华为智能平板')
    request('PUT', admin_url, update, TOKEN, 409)
    before = request('GET', search_url)['data']
    assert request('POST', admin_url + '/republish', token=TOKEN)['data']['version'] == version
    time.sleep(3)
    assert request('GET', search_url)['data'] == before
    print('通过：更新标题价格、乐观锁、同版本重放结果不变')
    update.update(status='OFF_SALE', expectedVersion=version)
    version = request('PUT', admin_url, update, TOKEN)['data']['version']
    await_items(lambda page: page['total'] == 0)
    print('通过：下架异步消失；测试商品=' + product_id)
finally:
    if created:
        try:
            current = request('GET', admin_url, token=TOKEN)['data']
            if current['status'] != 'OFF_SALE':
                cleanup = {key: current[key] for key in ('title', 'categoryId', 'brandId', 'priceCents')}
                cleanup.update(status='OFF_SALE', expectedVersion=current['version'])
                request('PUT', admin_url, cleanup, TOKEN)
        except Exception:
            print('测试商品下架清理失败，请检查商品：' + product_id, file=sys.stderr)
            raise

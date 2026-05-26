#!/bin/bash

echo "=== 排班查询接口测试 ==="

# 检测操作系统并设置日期命令
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    START_DATE=$(date +%Y-%m-%d)
    END_DATE=$(date -v+6d +%Y-%m-%d)
    END_DATE_INVALID=$(date -v+31d +%Y-%m-%d)
    START_DATE_FUTURE=$(date -v+7d +%Y-%m-%d)
else
    # Linux
    START_DATE=$(date +%Y-%m-%d)
    END_DATE=$(date -d "+6 days" +%Y-%m-%d)
    END_DATE_INVALID=$(date -d "+31 days" +%Y-%m-%d)
    START_DATE_FUTURE=$(date -d "+7 days" +%Y-%m-%d)
fi

echo ""
echo "测试日期范围: $START_DATE 到 $END_DATE"
echo ""

echo "1. 查询所有排班（未来7天）"
curl -s "http://localhost:8089/api/appointment/schedules?startDate=$START_DATE&endDate=$END_DATE" | jq '{success: .success, code: .code, count: (.data | length)}'

echo ""
echo "2. 按科室查询（儿科 1003）"
curl -s "http://localhost:8089/api/appointment/schedules?deptId=1003&startDate=$START_DATE&endDate=$END_DATE" | jq '{success: .success, count: (.data | length), allFromPediatrics: (.data | all(.deptName == "儿科"))}'

echo ""
echo "3. 按医生查询（医生 2005）"
curl -s "http://localhost:8089/api/appointment/schedules?doctorId=2005&startDate=$START_DATE&endDate=$END_DATE" | jq '{success: .success, count: (.data | length), allFromDoctor: (.data | all(.doctorId == 2005))}'

echo ""
echo "4. 日期范围校验（超过30天）"
curl -s "http://localhost:8089/api/appointment/schedules?startDate=$START_DATE&endDate=$END_DATE_INVALID" | jq '{success: .success, code: .code, message: .message}'

echo ""
echo "5. 日期范围校验（结束日期早于开始日期）"
curl -s "http://localhost:8089/api/appointment/schedules?startDate=$START_DATE_FUTURE&endDate=$START_DATE" | jq '{success: .success, code: .code, message: .message}'

echo ""
echo "6. 验证返回数据完整性"
curl -s "http://localhost:8089/api/appointment/schedules?startDate=$START_DATE&endDate=$END_DATE" | jq '.data[0] | {hasAllRequiredFields: (has("scheduleId") and has("deptId") and has("deptName") and has("doctorId") and has("doctorName") and has("title") and has("scheduleDate") and has("timeSlot") and has("totalQuota") and has("availableQuota") and has("feeCents") and has("status")), fieldCount: (keys | length)}'

echo ""
echo "7. 验证时段排序"
curl -s "http://localhost:8089/api/appointment/schedules?doctorId=2005&startDate=$START_DATE&endDate=$START_DATE" | jq '.data | map(.timeSlot)'

echo ""
echo "8. 查询单天排班详情"
curl -s "http://localhost:8089/api/appointment/schedules?startDate=$START_DATE&endDate=$START_DATE" | jq '.data[0] | {scheduleId, deptName, doctorName, scheduleDate, timeSlot, totalQuota, availableQuota, feeCents, status}'

echo ""
echo "=== 测试完成 ==="

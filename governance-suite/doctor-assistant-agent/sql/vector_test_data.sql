-- 向量数据库测试数据
-- 用于测试相似病例检索功能

USE doctor_assistant;

-- 注意：这些数据需要通过应用程序插入到 Milvus 中
-- 这里只是记录测试病例的文本描述

-- 测试病例 1: 高血压 + 减重成功
-- caseId: CASE_001
-- customerId: C001
-- description: 35岁女性患者，有高血压病史，通过饮食控制和运动，30天内成功减重3kg，血压控制良好
-- diagnosis: 高血压控制稳定，体重管理成功，建议继续保持健康生活方式
-- symptoms: 高血压, 超重
-- chronicDiseases: 高血压
-- medications: 厄贝沙坦片, 阿司匹林

-- 测试病例 2: 糖尿病 + 血糖控制
-- caseId: CASE_002
-- customerId: C002
-- description: 42岁男性患者，2型糖尿病，通过药物治疗和饮食调整，血糖控制在正常范围，体重略有下降
-- diagnosis: 糖尿病控制良好，血糖稳定，建议定期监测
-- symptoms: 高血糖, 肥胖
-- chronicDiseases: 糖尿病, 高血脂
-- medications: 二甲双胍, 阿托伐他汀

-- 测试病例 3: 焦虑 + 睡眠问题
-- caseId: CASE_003
-- customerId: C001
-- description: 35岁女性患者，最近出现焦虑情绪和睡眠质量下降，通过心理咨询和放松训练有所改善
-- diagnosis: 轻度焦虑，建议继续心理咨询，保持规律作息
-- symptoms: 焦虑, 失眠
-- chronicDiseases: 高血压
-- medications: 厄贝沙坦片

-- 测试病例 4: 减重计划
-- caseId: CASE_004
-- customerId: C003
-- description: 28岁女性患者，BMI正常但希望进一步减重，制定了科学的减重计划，包括饮食和运动
-- diagnosis: 健康状况良好，建议适度减重，避免过度节食
-- symptoms: 无
-- chronicDiseases: 无
-- medications: 无

-- 测试病例 5: 高血压 + 高血脂
-- caseId: CASE_005
-- customerId: C004
-- description: 50岁男性患者，高血压和高血脂，通过药物治疗和生活方式调整，指标逐步改善
-- diagnosis: 高血压和高血脂控制中，需要长期服药和定期复查
-- symptoms: 高血压, 高血脂, 头晕
-- chronicDiseases: 高血压, 高血脂
-- medications: 厄贝沙坦片, 阿托伐他汀, 阿司匹林

-- 测试病例 6: 糖尿病 + 肾功能异常
-- caseId: CASE_006
-- customerId: C005
-- description: 55岁女性患者，糖尿病10年，出现早期肾功能异常，调整用药方案，加强血糖监测
-- diagnosis: 糖尿病肾病早期，需要严格控制血糖，定期监测肾功能
-- symptoms: 高血糖, 蛋白尿
-- chronicDiseases: 糖尿病
-- medications: 二甲双胍, 胰岛素

-- 测试病例 7: 减重 + 运动损伤
-- caseId: CASE_007
-- customerId: C006
-- description: 30岁男性患者，减重过程中运动过度导致膝关节损伤，调整运动方案，改为低强度运动
-- diagnosis: 膝关节劳损，建议休息和物理治疗，减重计划需要调整
-- symptoms: 膝关节疼痛, 超重
-- chronicDiseases: 无
-- medications: 布洛芬

-- 测试病例 8: 高血压 + 心律不齐
-- caseId: CASE_008
-- customerId: C007
-- description: 60岁男性患者，高血压多年，最近出现心律不齐，调整降压药，增加心脏监测
-- diagnosis: 高血压合并心律不齐，需要综合治疗，定期心电图检查
-- symptoms: 高血压, 心悸, 胸闷
-- chronicDiseases: 高血压
-- medications: 厄贝沙坦片, 美托洛尔, 阿司匹林

-- 测试病例 9: 糖尿病 + 视网膜病变
-- caseId: CASE_009
-- customerId: C008
-- description: 58岁女性患者，糖尿病15年，出现糖尿病视网膜病变，需要眼科治疗和严格血糖控制
-- diagnosis: 糖尿病视网膜病变，需要激光治疗，严格控制血糖
-- symptoms: 视力下降, 高血糖
-- chronicDiseases: 糖尿病
-- medications: 二甲双胍, 胰岛素

-- 测试病例 10: 减重成功案例
-- caseId: CASE_010
-- customerId: C009
-- description: 38岁女性患者，通过科学的饮食和运动计划，6个月成功减重15kg，各项指标恢复正常
-- diagnosis: 减重成功，健康状况显著改善，建议保持当前生活方式
-- symptoms: 无
-- chronicDiseases: 无
-- medications: 无

-- 使用说明：
-- 1. 启动应用后，这些病例数据会自动生成向量并插入到 Milvus
-- 2. 可以通过 search_similar_cases 工具搜索相似病例
-- 3. 示例查询：
--    - 症状：["高血压", "超重"] -> 应该找到 CASE_001, CASE_005
--    - 症状：["高血糖", "肥胖"] -> 应该找到 CASE_002, CASE_006
--    - 症状：["焦虑", "失眠"] -> 应该找到 CASE_003

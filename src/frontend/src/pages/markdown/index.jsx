import { useState, useEffect } from "react";
import MarkdownEditor from "./editor";

export default function Markdown() {

    const [file, setFile] = useState("");
    const [value, setValue] = useState("");

    const handleFileChange = (filename) => {
        setFile(filename);
    };

    useEffect(() => {
        const files = {
            "file1.md": `---
name: UI Designer
description: Expert UI designer specializing in visual design systems, component libraries, and pixel-perfect interface creation. Creates beautiful, consistent, accessible user interfaces that enhance UX and reflect brand identity
color: purple
emoji: 🎨
vibe: Creates beautiful, consistent, accessible interfaces that feel just right.
---

# UI Designer Agent Personality
        `,
            "file2.md": `
# RBAC权限系统调研报告

## 执行摘要
本报告对基于角色的访问控制（RBAC）系统进行了全面调研分析。RBAC作为一种成熟的权限管理模型，通过角色作为用户和权限之间的中介层，有效解决了大型系统中权限管理的复杂性。调研发现，RBAC在政府、金融、医疗等对数据安全要求高的领域应用广泛，相比传统ACL模型具有更好的可扩展性和管理效率。

核心发现表明，完整的RBAC实现应包含角色继承（RBAC1）和约束机制（RBAC2），以应对复杂组织结构中的权限管理需求。在技术选型方面，Spring Security和Apache Shiro是Java生态的主流解决方案，分别适用于企业级应用和轻量级场景。

实施风险评估显示，角色爆炸和权限分配错误是主要挑战，建议通过角色粒度控制和审计机制缓解。市场趋势表明，RBAC正与属性访问控制（ABAC）融合，形成混合权限模型以适应云原生环境。

## 1. RBAC基本概念与核心模型

### 1.1 核心组件定义
RBAC模型包含三个基本实体：
- **用户(User)**：系统操作的主体
- **角色(Role)**：权限集合的载体
- **权限(Permission)**：资源操作的最小单元（如"文件:读取"）

\`\`\`mermaid
classDiagram
    User "1" -- "*" Role : 分配
    Role "1" -- "*" Permission : 包含
    Permission : +资源ID
    Permission : +操作类型
\`\`\`

### 1.2 关系模型
核心关系遵循NIST标准模型：
- 用户-角色分配（URA）
- 角色-权限分配（RPA）
- 角色-角色继承（RRI）

### 1.3 数据模型要求
| 实体        | 关键字段                  | 数据类型   | 约束条件         |
|-------------|--------------------------|------------|------------------|
| 用户        | user_id, dept_id         | UUID       | 唯一标识         |
| 角色        | role_id, role_level      | INT        | 角色等级≥0       |
| 权限        | res_id, action_type      | VARCHAR(50)| 操作类型枚举值    |
| 用户角色映射 | user_id, role_id         | 复合主键   | 级联删除         |
| 角色权限映射 | role_id, permission_id   | 复合主键   | 唯一约束         |

## 2. 常见RBAC实现模式

### 2.1 RBAC0 - 基础模型
\`\`\`mermaid
flowchart TD
    A[用户] --> B[分配角色]
    B --> C[角色关联权限]
    C --> D[访问控制决策]
\`\`\`
- **核心特征**：用户-角色-权限的静态绑定
- **适用场景**：小型系统或静态权限需求
- **限制**：不支持动态权限调整

### 2.2 RBAC1 - 角色继承
- 角色层级结构（例如：经理 > 主管 > 职员）
- 权限继承规则：
  \`\`\`mermaid
  flowchart LR
    A[经理] -- 继承 --> B[主管]
    B -- 继承 --> C[职员]
  \`\`\`
- **实施风险**：避免过度继承导致的权限泛滥

### 2.3 RBAC2 - 约束模型
- 主要约束类型：
  | 约束类别       | 作用                         | 示例                  |
  |----------------|------------------------------|-----------------------|
  | 互斥角色       | 防止权限冲突                 | 会计与审计员互斥     |
  | 基数约束       | 控制角色分配数量             | 系统管理员≤5人       |
  | 先决条件约束   | 要求前置角色                 | 晋升前需完成基础角色 |

### 2.4 RBAC3 - 完整模型
整合RBAC1和RBAC2的特性，支持：
- 角色继承链
- 动态约束检查
- 会话级角色激活

## 3. 典型应用场景分析

### 3.1 政府电子政务系统
**案例**：某省级政务平台
- 角色设计：按行政级别（厅级/处级/科级）
- 权限特点：
  - 数据敏感性分级（秘密/机密/绝密）
  - 跨部门角色互斥约束
- 实施效果：权限违规事件减少72%

### 3.2 金融核心系统
**案例**：银行信贷管理系统
- 风险控制机制：
  - 操作员与审批员角色分离
  - 敏感操作双人复核
  - 权限变更审计追踪
- 数据模型扩展：
  \`\`\`mermaid
  erDiagram
      USER ||--o{ ROLE : has
      ROLE ||--o{ PERMISSION : contains
      PERMISSION }|--|| BUSINESS_UNIT : belongs_to
  \`\`\`

### 3.3 医疗信息系统
**特殊需求**：
- HIPAA合规性要求
- 动态上下文权限（例如：急诊模式）
- 实施建议：
  - 基于患者状态的权限变更
  - 临时角色分配机制

## 4. 与传统ACL权限模型的对比

| 对比维度         | RBAC                     | ACL                     |
|------------------|--------------------------|-------------------------|
| 管理复杂度       | 低（角色中心）           | 高（用户/资源中心）     |
| 扩展性           | 支持千人级系统           | 适合百人以下规模       |
| 权限变更效率     | 修改角色影响所有关联用户 | 需逐个用户调整         |
| 审计能力         | 角色级审计轨迹           | 用户级操作日志         |
| 典型应用场景     | 企业管理系统             | 文件系统权限控制       |
| 实施成本         | 初期设计成本高           | 初期实现简单           |

\`\`\`mermaid
quadrantChart
    title "权限模型适用性分析"
    x-axis "低管理复杂度 --> 高管理复杂度"
    y-axis "小规模 --> 大规模"
    quadrant-1 "推荐ACL"
    quadrant-2 "推荐RBAC"
    quadrant-3 "不推荐"
    quadrant-4 "混合方案"
    "文件服务器": [0.2, 0.3]
    "OA系统(500人)": [0.7, 0.6]
    "ERP系统": [0.8, 0.75]
    "云存储服务": [0.4, 0.8]
\`\`\`

## 5. 实施风险评估与技术选型建议

### 5.1 主要风险
1. **角色爆炸**（超过200个角色）
   - 缓解方案：建立角色分类体系
   - 监控指标：角色/用户比 ≤ 1:5
   
2. **权限分配错误**
   - 防护机制：
     - 权限最小化原则
     - 变更审批工作流
     
3. **会话劫持**
   - 技术对策：
     - JWT令牌加密
     - 会话超时设置（建议≤30分钟）

### 5.2 选型决策矩阵
| 评估维度       | 权重 | Spring Security | Apache Shiro |
|----------------|------|-----------------|--------------|
| 功能完整性     | 30%  | 9/10            | 8/10         |
| 学习曲线       | 20%  | 6/10            | 9/10         |
| 云原生支持     | 25%  | 9/10            | 7/10         |
| 审计能力       | 15%  | 8/10            | 7/10         |
| 社区活跃度     | 10%  | 9/10            | 8/10         |
| **综合得分**   |      | **8.35**        | **7.75**     |

### 5.3 实施路线图
1. **准备阶段**（2-4周）
   - 权限矩阵设计
   - 角色粒度规划
   
2. **试点阶段**（4-8周）
   - 选择非关键系统验证
   - 建立基准测试指标
   
3. **推广阶段**（8-12周）
   - 分模块逐步迁移
   - 实施监控：
     \`\`\`mermaid
     flowchart TD
         A[权限变更请求] --> B[安全团队审批]
         B --> C{风险等级}
         C -->|高| D[CCB评审]
         C -->|中| E[部门负责人审批]
         C -->|低| F[自动执行]
     \`\`\`

## 6. 推荐技术栈

### 6.1 Spring Security
**适用场景**：
- 基于Spring的微服务架构
- 需要OAuth2集成
- 企业级审计需求

**核心组件**：
\`\`\`mermaid
classDiagram
    class RoleHierarchy {
        <<interface>>
        +getReachableRoles()
    }
    class MethodSecurity {
        +@PreAuthorize()
    }
    RoleHierarchy <|-- DefaultRoleHierarchy
    MethodSecurity --> RoleHierarchy
\`\`\`

**最佳实践**：
- 结合Spring Data JPA实现动态权限
- 启用Method Level Security注解

### 6.2 Apache Shiro
**轻量级优势**：
- 无依赖框架
- 配置简化（INI格式）
- 学习曲线平缓

**扩展建议**：
- 集成Redis缓存权限数据
- 自定义Realm实现：
  \`\`\`java
  public class JdbcRealm extends AuthorizingRealm {
      protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
          // 实现RBAC权限查询逻辑
      }
  }
  \`\`\`

### 6.3 混合架构方案
对于复杂系统建议：
\`\`\`mermaid
flowchart LR
    subgraph 核心业务系统
        A[Spring Security] --> B[RBAC服务]
    end
    subgraph 运维管理系统
        C[Apache Shiro] --> B
    end
    B --> D[统一权限数据库]
\`\`\`

## 附录：关键数据指标

### 权限模型性能对比
| 模型   | 用户规模 | 权限决策时延(ms) | 管理耗时(人月/千用户) |
|--------|----------|------------------|-----------------------|
| RBAC0  | 1,000    | 35±5             | 0.8                   |
| RBAC3  | 1,000    | 50±8             | 1.2                   |
| ACL    | 1,000    | 25±3             | 2.5                   |

### 行业采用率
| 行业   | RBAC采用率 | 年增长率 | 主要应用场景         |
|--------|------------|----------|----------------------|
| 金融   | 92%        | 8.7%     | 核心交易系统         |
| 医疗   | 78%        | 12.3%    | 电子病历管理         |
| 政府   | 85%        | 6.5%     | 政务审批系统         |
| 教育   | 65%        | 15.2%    | 校园资源管理         |
`
        };

        files[file] && setValue(files[file]);
    }, [file])

    return (
        <div className="h-full flex flex-col gap-4">
            <div className="flex gap-10">
                <button onPointerDown={() => handleFileChange("file1.md")}>文件1</button>
                <button onPointerDown={() => handleFileChange("file2.md")}>文件2</button>
                <button onPointerDown={() => handleFileChange("file2.js")}>文件3</button>
            </div>
            <div className="w-full flex-1" style={{border:"1px solid #ddd"}}>
                {file.includes(".md") && <MarkdownEditor value={value}/>}
            </div>
        </div>
    );
};
---
slug: auto-configuration
title: Spring Boot 自动配置的原理是什么？
module: framework-spring
tags: [Spring Boot, 自动配置, 条件注解, starter]
difficulty: 3
frequency: 3
related:
  - slug: bean-lifecycle
    type: PREREQUISITE
  - slug: bean-scope-thread-safety
    type: RELATED
  - slug: aop-proxy
    type: RELATED
---

## 电梯版回答

入口是 @SpringBootApplication，它由 @SpringBootConfiguration、@ComponentScan 和 @EnableAutoConfiguration 三个注解组合而成。@EnableAutoConfiguration 通过 @Import(AutoConfigurationImportSelector.class) 引入选择器，这个选择器去读类路径下 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 文件，里面登记着所有自动配置类的全限定名，再逐个用条件注解筛选。这个文件位置是 Spring Boot 2.7 引入的，2.7 之前清单写在 spring.factories 的 EnableAutoConfiguration 键下，2.7 两种方式并存并兼容，3.0 彻底移除了 spring.factories 的方式。筛选的关键是条件注解，比如 @ConditionalOnClass 确认依赖在类路径上、@ConditionalOnMissingBean 确认用户没有自己定义、@ConditionalOnProperty 确认开关打开；再用 @AutoConfigureAfter 之类控制先后顺序。其中 @ConditionalOnMissingBean 是「约定优于配置」又允许覆盖的核心：你不定义才给默认实现。排查用 --debug 打印自动配置报告，看 Positive matches 和 Negative matches。

## 展开讲解

### @SpringBootApplication 的拆解

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = { ... })
public @interface SpringBootApplication {
    Class<?>[] exclude() default {};
    String[] excludeName() default {};
}
```

三层含义：

- **@SpringBootConfiguration**：本质是一个 @Configuration，标记这是配置类。
- **@ComponentScan**：从主类所在包开始扫描 @Component / @Service / @Repository / @Controller 等。
- **@EnableAutoConfiguration**：开启自动配置，这才是「零配置」的来源。

### @EnableAutoConfiguration 如何生效

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration { }
```

关键在 `@Import(AutoConfigurationImportSelector.class)`。它实现了 `ImportSelector`，并且更进一步实现了 **`DeferredImportSelector`**——延迟导入。这个「延迟」是用户配置优先的底层保证：**所有普通的 @Configuration 和用户 @Bean 都注册完之后，才轮到自动配置选择器执行**。

`AutoConfigurationImportSelector` 的核心工作：

1. 读取类路径下所有 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件，得到候选自动配置类的全限定名列表。
2. 去重、排除（`@SpringBootApplication(exclude = ...)` 或 `spring.autoconfigure.exclude` 配置的类）。
3. 逐个判断条件注解，把通过的类名返回给容器去注册成 BeanDefinition。

### 登记文件的位置变迁

| 版本 | 自动配置清单的登记方式 |
|---|---|
| Spring Boot 2.6 及以前 | `META-INF/spring.factories` 的 `EnableAutoConfiguration` 键，值是以逗号分隔的全限定名列表 |
| Spring Boot 2.7 | 引入 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，一行一个全限定名；同时兼容 `spring.factories` 的旧方式，后者标记为废弃 |
| Spring Boot 3.0 及以后 | **移除** `spring.factories` 注册自动配置的方式，只认 `.imports` 文件 |

新文件的内容形如：

```
com.example.autoconfigure.MyServiceAutoConfiguration
com.example.autoconfigure.OtherAutoConfiguration
```

2.7 引入新文件的原因是：`spring.factories` 是一个「所有扩展点共用一个文件」的大杂烩，不同模块都想往里写，重复键、顺序、合并语义都容易出问题；而且它用的是 Properties 格式，对 IDE 和静态分析不友好。拆成专门的 `.imports` 文件后，格式更简单（纯文本、一行一个），职责也更清晰。同时 2.7 引入了 `@AutoConfiguration` 注解专门标记自动配置类。

### 条件注解才是关键

光有清单只是「候选池」，真正决定哪个生效的是条件注解。常用的一批都来自 `org.springframework.boot.autoconfigure.condition`：

| 注解 | 作用 |
|---|---|
| `@ConditionalOnClass` | 类路径上存在指定类才生效 |
| `@ConditionalOnMissingClass` | 类路径上不存在指定类才生效 |
| `@ConditionalOnBean` | 容器里已经有指定 Bean 才生效 |
| `@ConditionalOnMissingBean` | 容器里没有指定 Bean 才生效 |
| `@ConditionalOnProperty` | 指定配置项存在且值匹配才生效 |
| `@ConditionalOnWebApplication` | 应用类型是 Web 应用才生效 |
| `@ConditionalOnResource` | 指定资源文件存在才生效 |
| `@ConditionalOnSingleCandidate` | 指定类型只有一个候选 Bean（或一个 @Primary）才生效 |

顺序控制用 `@AutoConfigureAfter` / `@AutoConfigureBefore`（旧写法）或 `@AutoConfiguration(after = ..., before = ...)`（2.7 起的写法）。

一个典型的自动配置类长这样：

```java
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(MyProperties.class)
public class MyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MyTemplate myTemplate(MyProperties props) {
        return new MyTemplate(props);
    }
}
```

三行注解分别解决「依赖在不在」「配置怎么绑定」「用户有没有自己定义」三个问题。

### 用户配置为什么优先

这是自动配置最容易被误解的一点。`@ConditionalOnMissingBean` 的判断**只基于当前已经注册的 BeanDefinition**，而自动配置是通过 `DeferredImportSelector` 延迟处理的，执行顺序是：

```
① 解析主类上的 @ComponentScan，注册用户自己写的 @Component / @Configuration / @Bean
② 处理 @Import，但 DeferredImportSelector 被推迟到普通 @Import 之后
③ 自动配置选择器执行，此时用户的 BeanDefinition 已经在注册表里
④ 自动配置类的 @Bean 上如果有 @ConditionalOnMissingBean，发现用户已定义 → 整个方法跳过
```

所以「你配置就听你的，你不配置才给默认值」不是靠什么优先级覆盖，而是靠**注册顺序 + 存在性检查**实现的。

注意代价：`@ConditionalOnMissingBean` 对顺序敏感。如果在自动配置类上乱用 `@ConditionalOnBean`，可能因为依赖的自动配置还没执行而误判。这也是为什么要用 `@AutoConfigureAfter` 明确声明自动配置之间的先后关系。

### 调试自动配置

启动时加 `--debug` 会打印 `ConditionEvaluationReport`：

```
============================
CONDITIONS EVALUATION REPORT
============================

Positive matches:
-----------------
   MyAutoConfiguration matched:
      - @ConditionalOnClass found required class 'javax.sql.DataSource'

Negative matches:
-----------------
   MyAutoConfiguration#myTemplate did not match:
      - @ConditionalOnMissingBean found beans of type 'MyTemplate'
```

**Positive matches** 是生效的，**Negative matches** 是没生效的，看后者能快速定位「为什么我的配置没起来」。条件是「不满足」还是「不存在」也有细分标记，比如 `did not find`、`found different types`。

除了 `--debug`，还可以：

- 通过 Actuator 的 `/actuator/conditions` 端点查看
- 注入 `ConditionEvaluationReport` Bean 以编程方式读取

### 自己写 starter 的要点

**starter 的拆分方式**：

- 一个 `xxx-spring-boot-autoconfigure` 模块放自动配置类和 `@ConfigurationProperties` 类
- 一个 `xxx-spring-boot-starter` 模块只做依赖聚合（通常只有 pom，不含代码），让别人引一个坐标就带齐所有依赖

**自动配置类的写法**：

```java
@AutoConfiguration
@ConditionalOnClass(SomeLibrary.class)
@EnableConfigurationProperties(SomeProperties.class)
public class SomeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SomeClient someClient(SomeProperties properties) {
        return new SomeClient(properties.getEndpoint());
    }
}
```

要点：

1. 类上必须有 `@ConditionalOnClass`，否则依赖不在时加载配置类本身就会报 `NoClassDefFoundError`。
2. 提供默认 Bean 时必须加 `@ConditionalOnMissingBean`，把覆盖权留给用户。
3. 属性用 `@ConfigurationProperties` 绑定，提供合理的默认值，并生成配置元数据（`spring-boot-configuration-processor`）。
4. 登记到 `.imports` 文件；如果要兼容 Spring Boot 2.6 及更早版本，还要在 `spring.factories` 里登记一份。
5. 需要顺序时用 `@AutoConfiguration(after = ...)` 声明，不要依赖文件里的书写顺序。

## 追问链

### Q1: 自动配置类那么多，为什么不会互相冲突、也不会全部生效？

因为候选清单和最终生效是两件事。`.imports` 文件只是给出「候选池」，里面可能有几百个类。真正决定哪些生效的是每个自动配置类上的**条件注解**：

- `@ConditionalOnClass`：依赖的类不在类路径，直接跳过——所以引入了 Web 依赖才会装配 Web 相关配置
- `@ConditionalOnMissingBean`：用户已经定义了同类型 Bean，自动配置让位
- `@ConditionalOnProperty`：开关没打开就不生效
- `@ConditionalOnWebApplication`：非 Web 应用不会装配 Web 相关配置

条件不满足就整类跳过，所以虽然候选很多，实际生效的通常只是当前依赖组合下合理的那一小部分，冲突也主要靠 `@ConditionalOnMissingBean` 和顺序声明来化解。

#### Q1.1: 条件注解是怎么实现的？@ConditionalOnMissingBean 为什么能保证用户配置优先？

所有条件注解最终都通过 Spring 的 `@Conditional` 机制生效：每个条件注解上元标注了 `@Conditional(XXXCondition.class)`，`XXXCondition` 实现 `Condition` 接口的 `matches()` 方法，返回 `false` 时对应的配置类或 @Bean 方法就被跳过。

`@ConditionalOnMissingBean` 对应 `OnBeanCondition`，它检查的是 `BeanDefinitionRegistry` 里当前**已注册**的 BeanDefinition。它的判断是「存在性」而非「优先级」：找到同类型（或指定类型）的 BeanDefinition 就返回 `false`，让自动配置的默认实现不注册。

这一步能成立的前提，是用户自己的 Bean 必须先被注册。

##### Q1.1.1: 用户配置一定先于自动配置注册吗？靠什么保证？

靠 **`DeferredImportSelector` 的延迟执行**。`AutoConfigurationImportSelector` 实现的是 `DeferredImportSelector` 而不是普通的 `ImportSelector`，Spring 处理 `@Import` 时会把延迟导入的选择器**攒到最后**，等所有常规配置类（包括主类 @ComponentScan 扫出来的用户配置）都注册完，再统一执行。

执行顺序大致是：

```
用户 @ComponentScan / @Configuration / @Bean  →  注册用户的 BeanDefinition
        ↓
DeferredImportSelector 统一执行（自动配置）
        ↓
自动配置里的 @ConditionalOnMissingBean 看到用户已定义 → 跳过默认实现
```

如果自动配置和用户配置同时处理、或者自动配置在前，`@ConditionalOnMissingBean` 就会误判，用户的定义可能被默认实现「抢先」或者产生两个同类型 Bean。这也是为什么**不应该把 `@ConditionalOnMissingBean` 用在普通用户配置类上**——它只在自动配置这种有明确顺序保证的场景里可靠。

### Q2: 2.7 起为什么把清单从 spring.factories 挪到 imports 文件？

`spring.factories` 是 Properties 格式的键值对文件，早期承担了所有 Spring Boot 扩展点的注册职责：自动配置、`ApplicationContextInitializer`、`ApplicationListener`、`EnvironmentPostProcessor` 等等全挤在同一个文件里，键是接口全限定名，值是以逗号分隔的实现类列表。

问题有三个：

1. **职责混杂**：一个文件既是自动配置清单又是各种扩展点注册表，任何模块都要往里加内容，容易冲突。
2. **合并语义不直观**：多个 jar 里同名键的值会被合并，顺序、去重依赖具体实现，排查问题困难。
3. **对工具不友好**：Properties 格式里一长串逗号分隔的全限定名，IDE 导航和静态检查都不方便。

`.imports` 文件改成纯文本、一行一个类名，职责单一（就是自动配置清单），顺序显式，工具也好处理。同时引入 `@AutoConfiguration` 注解替代「靠约定识别自动配置类」，语义更清楚。

#### Q2.1: 那 spring.factories 现在还有用吗？

有用，但**不再用于登记自动配置**。

在 Spring Boot 3.0 里，`spring.factories` 中的 `EnableAutoConfiguration` 键被移除，写在那里不会再被当作自动配置加载。但 `spring.factories` 这个机制本身还在，其他扩展点仍然可以用它注册，例如：

- `EnvironmentPostProcessor`
- `ApplicationContextInitializer`
- `ApplicationListener`
- `FailureAnalyzer`
- `AutoConfigurationImportFilter`（这个比较特殊，属于自动配置流程的过滤器）
- `PropertySourceLoader`

也就是说 3.0 移除的是「自动配置这一种用法」，不是整个 `spring.factories` 机制。要区分清楚这个边界。

##### Q2.1.1: 自己写 starter 时，自动配置类应该登记在哪里？

取决于要兼容的 Spring Boot 版本：

| 目标版本 | 登记位置 |
|---|---|
| 2.7 及以上（含 3.x） | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| 2.6 及更早 | `META-INF/spring.factories` 的 `EnableAutoConfiguration` 键 |
| 同时兼容 2.x 和 3.x | 两个文件都提供 |

如果只面向 Spring Boot 3，就只写 `.imports`，同时在自动配置类上使用 `@AutoConfiguration` 并配合 `@AutoConfigureAfter`（旧注解在 3.x 仍可用）或 `@AutoConfiguration(after = ...)`。注意 `.imports` 文件里**一行一个全限定名，不能加注释也不能用逗号分隔**，这一点和 `spring.factories` 完全不同。

### Q3: 上线后发现某个自动配置没生效，怎么排查？

首选 `--debug`（或配置 `debug: true`）打印 `ConditionEvaluationReport`：

```
java -jar app.jar --debug
```

先看 **Negative matches** 里有没有目标自动配置类：

- 如果类根本没出现在报告里，说明它没在 `.imports` 文件里登记，或者被 `exclude` 排除了
- 如果出现在 Negative matches，报告会写明是哪个条件不满足，例如 `@ConditionalOnClass did not find required class 'xxx'`（依赖没引入）、`@ConditionalOnMissingBean found beans of type 'Xxx'`（用户已定义导致自动配置让位）
- 如果出现在 Positive matches 但功能仍不正常，说明配置类生效了，但绑定的属性值不对，去查 `@ConfigurationProperties` 的绑定结果和配置项前缀

另外注意：报错里的 `did not find` 和 `found different types` 含义不同，前者是「不存在」，后者是「存在但类型不符」，排查方向不一样。

#### Q3.1: 如果条件是满足的，但装配出来的 Bean 不对呢？

那说明问题不在条件判断，而在**属性绑定**或**Bean 覆盖**。排查方向：

1. 检查 `@ConfigurationProperties` 的前缀是否写对，属性名是否遵循宽松绑定（relaxed binding，`my-app.endpoint` 和 `myApp.endpoint` 都能绑定）。
2. 确认没有多个同类型 Bean 造成注入歧义——`@ConditionalOnMissingBean` 只保证「没有才补」，如果你自己定义了同类型的第二个 Bean，就可能出现 `NoUniqueBeanDefinitionException`。
3. 用 Actuator 的 `/actuator/beans` 看容器里实际有哪些 Bean、来自哪个配置类。

##### Q3.1.1: 为什么条件注解在测试里表现和线上不一样？

因为条件注解的判断依据是**运行期的类路径、BeanDefinition 注册表和配置属性**，三者都会随环境变化：

- **类路径**：测试时可能引入了额外的 test 依赖，让 `@ConditionalOnClass` 意外匹配；或者用了 `@SpringBootTest` 的切片（如 `@WebMvcTest`）导致某类 Bean 没被注册，`@ConditionalOnBean` 判断结果不同。
- **BeanDefinition 注册表**：切片测试只加载部分配置，`@ConditionalOnMissingBean` 看到的 Bean 集合和线上完全不同。
- **配置属性**：测试用的 profile、`application-test.yml` 会改变 `@ConditionalOnProperty` 的结果。

结论是：**条件注解的评估结果依赖应用上下文在那一刻的状态，不是静态的**。测试里要覆盖自动配置行为，应该显式控制类路径和上下文，而不是期望它和线上一致。

## 常见坑

- **说「@EnableAutoConfiguration 会把 .imports 文件里所有自动配置类都加载成 Bean」** —— 文件里只是候选池，每个类还要过条件注解，不满足条件的整类跳过
- **说「自动配置类先注册，用户配置再覆盖它」** —— 顺序是反的。自动配置通过 DeferredImportSelector 延迟到用户配置之后处理，`@ConditionalOnMissingBean` 是因为看到用户已定义才让位，不存在「覆盖」动作
- **说「spring.factories 在 Spring Boot 3 里仍是自动配置的入口」** —— 3.0 已移除 `EnableAutoConfiguration` 键的加载，自动配置只认 `.imports` 文件；但 `spring.factories` 机制本身仍用于其他扩展点
- **说「2.7 之后 spring.factories 直接被删了」** —— 2.7 是两种方式并存兼容，3.0 才移除自动配置的旧方式
- **把 `@ConditionalOnMissingBean` 随便用在用户自己的配置类上** —— 它只基于当前已注册的 BeanDefinition 判断，对顺序敏感，在用户配置之间没有可靠保证，它是为自动配置设计的
- **认为 `@ConditionalOnClass` 可以省略** —— 不写的话，依赖不在类路径时加载自动配置类本身就会触发 `NoClassDefFoundError`，条件判断反而失效
- **说「自动配置类的顺序由 .imports 文件里的书写顺序决定」** —— 顺序要靠 `@AutoConfigureAfter` / `@AutoConfigureBefore` 或 `@AutoConfiguration(after = ...)` 显式声明
- **把 `did not find` 和 `found different types` 当成一回事** —— 前者是目标不存在，后者是存在但类型不匹配，排查方向不同

## 加分点

- 能点明 **`DeferredImportSelector` 才是「用户配置优先」的实现机制**，而不是笼统地说「Spring Boot 有优先级」
- 知道 `AutoConfigurationImportSelector` 除了延迟导入，还支持 **`AutoConfigurationImportFilter`** 做早期快速过滤（比如 `OnClassCondition` 先按类路径过滤一遍，避免为每个候选类都做完整的条件评估），这是一个性能优化
- 能区分 **`spring.factories` 被移除的是「自动配置用法」而不是整个机制**，并列出仍在使用它的扩展点（`EnvironmentPostProcessor`、`FailureAnalyzer` 等）
- 知道 **2.7 引入的 `@AutoConfiguration` 是元注解**，它本身携带 `@Configuration(proxyBeanMethods = false)`，所以自动配置类不需要（也不应该）额外标 `@Configuration`
- 提到 **条件评估报告性能**：条件注解的评估在启动期有实打实的成本，候选类很多时启动会变慢，`AutoConfigurationImportFilter` 和 `@ConditionalOnClass` 的早期过滤就是为此
- 知道 **`@ConditionalOnMissingBean` 的检查范围**是当前注册表，而不是「最终容器里的所有 Bean」，所以它判断不了尚未注册的、由其他自动配置后续提供的 Bean
- 写 starter 时能说出**自动配置模块与 starter 模块分离**的惯例：`xxx-spring-boot-autoconfigure` 放代码，`xxx-spring-boot-starter` 只做依赖聚合
- 提到 **宽松绑定（relaxed binding）**：`@ConfigurationProperties` 支持 `my-app.endpoint`、`myApp.endpoint`、`MY_APP_ENDPOINT` 等多种写法映射到同一属性

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring Boot 2.6 及以前 | 自动配置清单写在 `META-INF/spring.factories` 的 `EnableAutoConfiguration` 键下 |
| Spring Boot 2.7 | 引入 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；引入 `@AutoConfiguration` 注解；两种登记方式并存兼容，`spring.factories` 方式废弃 |
| Spring Boot 3.0 | **移除** `spring.factories` 登记自动配置的方式，只保留 `.imports` 文件；同时迁移到 `jakarta.*` 命名空间，Java 基线升到 17 |
| Spring Boot 3.x | `.imports` 文件格式与位置保持稳定；`@AutoConfigureAfter` / `@AutoConfigureBefore` 仍可用，推荐用 `@AutoConfiguration(after = ...)` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: Spring Boot 3.0 中，自动配置类的候选清单登记在哪个文件里？
    options:
      A: META-INF/spring.factories 的 EnableAutoConfiguration 键
      B: META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      C: META-INF/spring-configuration-metadata.json
      D: META-INF/application.properties
    answer: B
    analysis: 2.7 引入 AutoConfiguration.imports 文件并在 3.0 完全取代 spring.factories 的自动配置用法。spring-configuration-metadata.json 描述的是配置项元数据，不是自动配置清单。

  - type: JUDGE
    stem: "@ConditionalOnMissingBean 能保证用户自己定义的 Bean 优先于自动配置提供的默认实现。"
    answer: T
    analysis: 自动配置通过 DeferredImportSelector 延迟到用户配置之后处理，此时用户的 BeanDefinition 已注册，@ConditionalOnMissingBean 检测到同类 Bean 就跳过默认实现，从而实现「你不定义才给默认值」。

  - type: MULTI
    stem: 关于 Spring Boot 自动配置，下列说法正确的有？
    options:
      A: "@SpringBootApplication 是 @SpringBootConfiguration、@ComponentScan、@EnableAutoConfiguration 的组合"
      B: 候选清单只是候选池，还要经过条件注解筛选才会生效
      C: 自动配置之间的先后顺序可以用 @AutoConfigureAfter 控制
      D: 2.7 之后 spring.factories 机制被整个删除，不能再注册任何扩展点
    answer: ABC
    analysis: D 错误。3.0 移除的是用 spring.factories 登记自动配置这一种用法，spring.factories 机制本身仍用于注册 EnvironmentPostProcessor、FailureAnalyzer 等扩展点。

  - type: CLOZE
    stem: |
      补全下面这个自动配置类的条件注解与默认 Bean 保护：
      ```java
      @AutoConfiguration
      @ConditionalOn{{1}}(DataSource.class)
      @EnableConfigurationProperties(MyProperties.class)
      public class MyAutoConfiguration {

          @Bean
          @ConditionalOn{{2}}
          public MyTemplate myTemplate(MyProperties props) {
              return new MyTemplate(props);
          }
      }
      ```
    blanks:
      - ["Class"]
      - ["MissingBean"]
    analysis: 类上加 @ConditionalOnClass 保证依赖不在类路径时不会加载配置类而报 NoClassDefFoundError；@Bean 方法上加 @ConditionalOnMissingBean 保证用户自己定义同类型 Bean 时自动配置让位。
    difficulty: 3
````

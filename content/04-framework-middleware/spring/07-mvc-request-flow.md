---
slug: mvc-request-flow
title: 一个 HTTP 请求到 Controller 经历了什么？
module: framework-spring
tags: [Spring MVC, DispatcherServlet, 拦截器, 参数解析]
difficulty: 3
frequency: 3
related:
  - slug: aop-proxy
    type: RELATED
  - slug: bean-lifecycle
    type: RELATED
  - slug: auto-configuration
    type: RELATED
---

## 电梯版回答

请求先经过 Servlet 容器里的 Filter 链，进入 DispatcherServlet 这个前端控制器，核心方法是 doDispatch。它先用 HandlerMapping 找到 HandlerExecutionChain，里面装着真正要执行的 handler 和拦截器链；再找 HandlerAdapter 来适配调用，因为 handler 不一定是注解方法，也可能是 HttpRequestHandler 或普通 Controller 接口。接着依次执行拦截器的 preHandle；进入调用阶段，参数由 HandlerMethodArgumentResolver 解析，@RequestBody 会走 HttpMessageConverter 反序列化；然后反射调用 Controller 方法；返回值由 HandlerMethodReturnValueHandler 处理，带 @ResponseBody 的走 RequestResponseBodyMethodProcessor 直接把对象写进响应，否则得到 ModelAndView 交给 ViewResolver 解析视图再渲染。之后执行 postHandle，渲染完成后执行 afterCompletion。整个过程中抛出的异常由 HandlerExceptionResolver 处理，@ControllerAdvice 配合 @ExceptionHandler 就是其中一种实现。

## 展开讲解

### DispatcherServlet 的定位

DispatcherServlet 是**前端控制器（Front Controller）**，继承自 `HttpServlet`，是整个 Spring MVC 的统一入口，所有请求都由它分派。它本身不做业务，只负责「找到该谁处理、把参数准备好、把结果写出去」。

请求进入 DispatcherServlet 的路径：

```
HTTP 请求
  → Servlet 容器的 FilterChain（字符编码、跨域、认证等 Filter）
  → DispatcherServlet.doDispatch()
  → Spring MVC 内部处理
  → 响应
```

### doDispatch 主流程

`DispatcherServlet#doDispatch` 是最值得背下来的一段源码，它的主干大致是：

```java
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;
    ModelAndView mv = null;
    Exception dispatchException = null;
    try {
        processedRequest = checkMultipart(request);              // 文件上传解析

        mappedHandler = getHandler(processedRequest);            // ① 找 handler
        if (mappedHandler == null) {
            noHandlerFound(processedRequest, response);
            return;
        }

        HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());  // ② 找适配器

        if (!mappedHandler.applyPreHandle(processedRequest, response)) {    // ③ preHandle
            return;                                                          //    返回 false 直接结束
        }

        mv = ha.handle(processedRequest, response, mappedHandler.getHandler());  // ④ 调用 handler

        applyDefaultViewName(processedRequest, mv);
        mappedHandler.applyPostHandle(processedRequest, response, mv);      // ⑤ postHandle
    } catch (Exception ex) {
        dispatchException = ex;
    }
    processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException);
}
```

`processDispatchResult` 做两件事：

- 如果有异常，交给 `HandlerExceptionResolver` 处理
- 否则渲染 `ModelAndView`；渲染完成后调用 `mappedHandler.triggerAfterCompletion(...)`

### 六个阶段拆开看

**① HandlerMapping 找 handler**

`HandlerMapping` 的职责是把请求映射到 `HandlerExecutionChain`（Handler + 拦截器链）。常见实现：

| 实现 | 作用 |
|---|---|
| `RequestMappingHandlerMapping` | 处理 @RequestMapping / @GetMapping 等注解方法，最常用 |
| `BeanNameUrlHandlerMapping` | 按 Bean 名字匹配 URL |
| `SimpleUrlHandlerMapping` | 显式配置 URL 到 handler 的映射 |
| `RouterFunctionMapping` | 函数式端点（Spring 5.2 起） |

`HandlerExecutionChain` 里的 handler 有两部分信息：真正处理请求的对象（`Object handler`），以及一组 `HandlerInterceptor`。拦截器是在这里就被组装好的，不是调用时才找。

**② HandlerAdapter 适配调用**

为什么不直接反射调用？因为 handler 的类型不统一：可能是注解方法（`HandlerMethod`）、可能是实现 `Controller` 接口的旧式类、也可能是 `HttpRequestHandler`。`HandlerAdapter` 是一个适配层，把「不同的 handler 类型」统一成 `handle(request, response, handler)` 这一个入口。`RequestMappingHandlerAdapter` 负责注解方法，`HttpRequestHandlerAdapter` 负责 `HttpRequestHandler`。

**③ 参数解析：HandlerMethodArgumentResolver**

注解方法的具体调用由 `RequestMappingHandlerAdapter#invokeHandlerMethod` 完成，它把 handler 包装成 `ServletInvocableHandlerMethod`，然后遍历方法参数，为每个参数找一个 `HandlerMethodArgumentResolver`：

```
方法签名：
public User get(@PathVariable Long id, @RequestParam String name, @RequestBody UserQuery q)

参数解析：
  id   → PathVariableMethodArgumentResolver（从 URI 模板取值）
  name → RequestParamMethodArgumentResolver（从查询参数取值）
  q    → RequestResponseBodyMethodProcessor（读请求体，用 HttpMessageConverter 反序列化）
```

`@RequestBody` 的处理就是在这里发生的：`RequestResponseBodyMethodProcessor` 会遍历注册的 `HttpMessageConverter`，找到能读该 Content-Type 的转换器（JSON 通常是 `MappingJackson2HttpMessageConverter`），把请求体反序列化成方法参数。**请求体只能读一次**，所以一个方法里不能有两个 `@RequestBody`。

**④ 反射调用 Controller**

`ServletInvocableHandlerMethod#invokeForRequest` 把解析好的参数数组传给底层 `Method#invoke`，真正执行你的 Controller 方法。

**⑤ 返回值处理：HandlerMethodReturnValueHandler**

返回值也要经过统一的处理链：

| 返回值情况 | 处理器 | 结果 |
|---|---|---|
| 方法带 `@ResponseBody` | `RequestResponseBodyMethodProcessor` | 对象经 `HttpMessageConverter` 序列化后**直接写入响应**，不走视图 |
| 返回 `ResponseEntity` | `HttpEntityMethodProcessor` | 写状态码、响应头、响应体 |
| 返回 `String`（不带 @ResponseBody） | `ViewNameMethodReturnValueHandler` | 当作视图名，交给 ViewResolver |
| 返回 `ModelAndView` | `ModelAndViewMethodReturnValueHandler` | 直接就是视图和模型 |

注意 `@ResponseBody` 在 `@RestController` 上是默认的——`@RestController = @Controller + @ResponseBody`，所以 REST 接口的返回值都走消息转换器，不会去找视图。

**⑥ 视图解析与渲染**

没有 `@ResponseBody` 时，得到 `ModelAndView`，交给 `ViewResolver` 解析成 `View`（比如 `InternalResourceViewResolver` 把 `"user/list"` 解析成 `/WEB-INF/views/user/list.jsp`），然后 `view.render(model, request, response)` 完成渲染。

### DispatcherServlet 的九大组件

`DispatcherServlet#initStrategies` 初始化九个策略组件（从 WebApplicationContext 里找，找不到就用默认实现）：

| 组件 | 职责 |
|---|---|
| `MultipartResolver` | 解析 multipart 请求，处理文件上传 |
| `LocaleResolver` | 解析请求的 Locale，用于国际化 |
| `ThemeResolver` | 解析主题（较少用） |
| `HandlerMapping` | 请求到 HandlerExecutionChain 的映射 |
| `HandlerAdapter` | 适配不同类型 handler 的调用 |
| `HandlerExceptionResolver` | 异常到响应的解析 |
| `RequestToViewNameTranslator` | 没有显式返回视图名时，从请求 URL 推导视图名 |
| `ViewResolver` | 视图名到 View 的解析 |
| `FlashMapManager` | 管理重定向时的 flash 属性（跨重定向传参） |

这九个里最核心、面试最常问的是 HandlerMapping、HandlerAdapter、HandlerExceptionResolver、ViewResolver 四个。

### Filter 与 Interceptor 的区别

两者都能「在请求处理前后插一段逻辑」，但层级完全不同：

| 维度 | Filter | Interceptor |
|---|---|---|
| 规范归属 | Servlet 规范 | Spring MVC |
| 管理容器 | Servlet 容器（Tomcat） | Spring 容器 |
| 拦截范围 | 所有匹配 urlPatterns 的请求 | 只拦截进入 DispatcherServlet 且被 HandlerMapping 匹配的请求 |
| 执行位置 | DispatcherServlet **之前** | Handler 调用**前后**，也就是 DispatcherServlet 内部 |
| 能否拿到 Spring Bean | 默认拿不到，需注册为 Bean 或用 `DelegatingFilterProxy` | 本身就是 Spring Bean，可以直接注入依赖 |
| 能否包装 request/response | 可以（`HttpServletRequestWrapper`） | 通常只读请求信息，不能改请求体 |

执行顺序：

```
Filter#doFilter 前置
  → DispatcherServlet.doDispatch
      → Interceptor#preHandle
          → Controller 方法
      → Interceptor#postHandle
      → 视图渲染
      → Interceptor#afterCompletion
  → Filter#doFilter 后置
```

选择建议：**字符编码、跨域、请求日志、安全过滤这类与框架无关、需要操作原始请求的用 Filter；权限校验、参数预处理、统一日志这类需要拿 Spring Bean 和知道 Handler 信息的用 Interceptor。**

### 异常处理链路

handler 抛出的异常被 `doDispatch` 的 catch 捕获后，交给 `processHandlerException`，依次问每个 `HandlerExceptionResolver` 能不能处理：

| 实现 | 作用 |
|---|---|
| `ExceptionHandlerExceptionResolver` | 处理 `@ExceptionHandler` 标注的方法 |
| `ResponseStatusExceptionResolver` | 处理带 `@ResponseStatus` 的异常 |
| `DefaultHandlerExceptionResolver` | 处理 Spring MVC 的标准异常（如 `HttpRequestMethodNotSupportedException`） |
| `HandlerExceptionResolverComposite` | 把上面几个组合起来按顺序问 |

`@ControllerAdvice` + `@ExceptionHandler` 的机制正是由 `ExceptionHandlerExceptionResolver` 实现：它先从当前 Controller 里找 `@ExceptionHandler` 方法，找不到再去所有 `@ControllerAdvice` Bean 里找最匹配的（按异常类型的继承关系选最近的）。所以 `@ControllerAdvice` 是一个全局异常处理器的载体，本质还是被这个 Resolver 驱动。

`@ControllerAdvice` 还能配合 `@ModelAttribute`、`@InitBinder` 做全局数据绑定和类型转换注册。

### 参数注解的差别

| 注解 | 数据来源 | 典型场景 |
|---|---|---|
| `@RequestParam` | 查询字符串 / 表单字段 | `?name=xx` |
| `@PathVariable` | URI 模板变量 | `/users/{id}` |
| `@ModelAttribute` | 查询参数/表单字段绑定成对象，并放入 Model | 表单提交 |
| `@RequestBody` | 请求体，经 HttpMessageConverter 反序列化 | JSON 请求体 |
| `@RequestHeader` / `@CookieValue` | 请求头 / Cookie | 认证信息 |
| `@RequestPart` | multipart 的某个 part | 文件加 JSON 混合提交 |

几个容易混的点：

- `@RequestParam` 和 `@RequestBody` 可以同时用，前者取值来源是 URL/表单，后者是请求体，两者不冲突。
- `@ModelAttribute` 不带 `@RequestBody` 时会走数据绑定和类型转换，绑定失败会抛 `BindException`，常配合 `BindingResult` 收集错误。
- `@RequestBody` 不能有多个，因为请求体只能读一次。

## 追问链

### Q1: DispatcherServlet 是怎么知道该由哪个 Controller 方法处理的？

通过 `HandlerMapping`。以最常用的 `RequestMappingHandlerMapping` 为例，它在启动时就扫描所有 `@Controller` / `@RequestMapping` 方法，把每个映射条件（URL 路径、HTTP 方法、请求参数、header、consumes/produces 等）解析成 `RequestMappingInfo`，建立「映射条件 → HandlerMethod」的注册表。

请求到来时，它拿请求的各项特征去匹配这张表，命中后返回一个 `HandlerExecutionChain`，里面包含目标 `HandlerMethod` 和该请求路径匹配到的拦截器列表。

#### Q1.1: 那 HandlerAdapter 是干嘛的？找到 handler 直接反射调用不行吗？

因为 handler 的类型不止一种。Spring MVC 里 handler 可能是：

- 注解方法（`HandlerMethod`，最常见）
- 实现旧版 `Controller` 接口的类
- `HttpRequestHandler` 实现
- 函数式端点（`RouterFunction`）

这些类型的调用方式完全不同，DispatcherServlet 不想为每种类型写一个 if-else 分支，于是用 `HandlerAdapter` 抽象出统一的 `handle(request, response, handler)`。

判断用哪个 Adapter 的办法是 `supports(handler)`：`RequestMappingHandlerAdapter.supports()` 判断是不是 `HandlerMethod`，`HttpRequestHandlerAdapter.supports()` 判断是不是 `HttpRequestHandler`。找到第一个支持的适配器来调用。这是一个典型的适配器模式。

##### Q1.1.1: 那 Controller 方法的参数是怎么从 HTTP 请求变成实参的？

由 `HandlerMethodArgumentResolver` 链完成。`RequestMappingHandlerAdapter` 启动时会装配一批默认解析器，调用时遍历方法参数，为每个参数找到第一个 `supportsParameter(parameter)` 返回 true 的解析器，调用它的 `resolveArgument()`。

比如：

- `@PathVariable Long id` → `PathVariableMethodArgumentResolver` 从 URI 模板变量里取值，并做类型转换（`Long` 由 `ConversionService` 转换）
- `@RequestParam String name` → `RequestParamMethodArgumentResolver` 从请求参数里取值
- `@RequestBody UserQuery q` → `RequestResponseBodyMethodProcessor` 读请求体，用 `HttpMessageConverter` 反序列化

参数解析完之后，`ServletInvocableHandlerMethod` 把参数数组交给反射调用。

### Q2: Filter 和 Interceptor 都能拦截请求，到底有什么区别，该用哪个？

最本质的区别是**规范层级和执行位置**：

- Filter 属于 Servlet 规范，由 Servlet 容器管理，执行在 DispatcherServlet **之前**，甚至不知道 Spring MVC 的存在。
- Interceptor 属于 Spring MVC，执行在 DispatcherServlet **内部**，能拿到 handler 信息和 Spring 容器里的 Bean。

由此派生出一串差异：Filter 能包装 request/response，能做字符编码、跨域这类底层处理；Interceptor 能注入 Service 做权限校验，但拿不到「修改后的请求体」。拦截范围也不同，Filter 会拦下所有匹配的 URL，Interceptor 只拦被 HandlerMapping 匹配到的请求（静态资源可能配了别的 handler mapping，可以被排除）。

#### Q2.1: 拦截器的 postHandle 什么时候不会执行？

两种情况：

1. **preHandle 返回 false**：请求直接被截断，既不会调用 handler，也不会执行 postHandle。而且此时对**该拦截器**来说 afterCompletion 也不会执行——只有 preHandle 返回过 true 的拦截器，afterCompletion 才会被回调。
2. **handler 抛出异常**：`ha.handle()` 抛异常后，`applyPostHandle` 这一行不会被执行，postHandle 被跳过。

需要特别注意第 2 种：很多人以为「postHandle 一定会执行」，然后在里面做资源清理，结果异常路径下资源没释放。正确的位置是 `afterCompletion`。

##### Q2.1.1: 那 afterCompletion 呢？抛异常的时候会执行吗？

会。`afterCompletion` 在 `processDispatchResult` 的最后被调用，而 `processDispatchResult` 无论有没有异常都会走到：

```java
private void processDispatchResult(...) {
    if (exception != null) {
        mv = processHandlerException(...);   // 先处理异常
    }
    // ... 渲染视图（可能是异常解析出的错误视图）
    if (mappedHandler != null) {
        mappedHandler.triggerAfterCompletion(request, response, null);  // 再回调 afterCompletion
    }
}
```

所以异常路径下 postHandle 跳过、afterCompletion 照常。前提是该拦截器的 preHandle 曾经返回 true——如果它连 preHandle 都没通过，就轮不到它收尾。这个设计很像 try-finally：**afterCompletion 是 finally 块，适合释放资源；postHandle 是正常路径的后置处理，适合修改 ModelAndView。**

### Q3: Controller 里抛异常之后，是谁把它变成响应的？

`HandlerExceptionResolver`。`doDispatch` 用 catch 把异常捕获到 `dispatchException`，交给 `processDispatchResult`，后者调用 `processHandlerException`，按顺序询问每个 Resolver：

1. `ExceptionHandlerExceptionResolver`：找 `@ExceptionHandler` 方法
2. `ResponseStatusExceptionResolver`：处理 `@ResponseStatus` 和 `ResponseStatusException`
3. `DefaultHandlerExceptionResolver`：处理 Spring MVC 内置异常，比如请求方法不支持、参数类型不匹配

任意一个返回了非 null 的 `ModelAndView` 就表示处理完毕，异常不会再往上抛；全都不处理，才会交给 Servlet 容器（最终变成容器的错误页或 500）。

#### Q3.1: @ControllerAdvice 是怎么被找到的？

`ExceptionHandlerExceptionResolver` 在初始化时会从 `ApplicationContext` 里获取所有标注了 `@ControllerAdvice`（以及 `@RestControllerAdvice`）的 Bean，为每个 Bean 建立 `ExceptionHandlerMethodResolver`。异常发生时，它先查当前 Controller 自己有没有匹配的 `@ExceptionHandler`，没有再按 `@ControllerAdvice` 的匹配范围（`basePackages`、`assignableTypes`、`annotations`）过滤，找到最匹配的 `@ExceptionHandler` 方法。

选择匹配方法的规则是按**异常类型的继承关系找最近的**：抛 `NullPointerException` 时，如果同时有处理 `RuntimeException` 和处理 `Exception` 的方法，会选 `RuntimeException` 那个。

##### Q3.1.1: 为什么 @ExceptionHandler 方法的返回值也能走消息转换器？

因为 `ExceptionHandlerExceptionResolver` 在处理 `@ExceptionHandler` 方法时，**复用了和 Controller 相同的调用基础设施**：它内部持有 `ServletInvocableHandlerMethod`（用来解析 `@ExceptionHandler` 方法的参数）和 `HandlerMethodReturnValueHandlerComposite`（用来处理返回值）。这套返回值处理器和正常请求用的是同一套，所以：

- 方法标了 `@ResponseBody`（或所在类是 `@RestControllerAdvice`）→ 走 `RequestResponseBodyMethodProcessor`，用 `HttpMessageConverter` 序列化
- 没标 → 返回的 `ModelAndView` 交给 `ViewResolver`

同理，`@ExceptionHandler` 方法的参数也支持 `HttpServletRequest`、`Exception`、`Model` 等，由参数解析器统一提供。这就是为什么异常处理方法的写法和 Controller 方法几乎一模一样。

## 常见坑

- **说「Filter 和 Interceptor 是同一层的东西，只是名字不同」** —— Filter 属于 Servlet 规范、在 DispatcherServlet 之前；Interceptor 属于 Spring MVC、在 DispatcherServlet 内部，执行的上下文和能力都不同
- **说「Interceptor 的 postHandle 一定会执行」** —— preHandle 返回 false 或 handler 抛异常时 postHandle 都不会执行；要放在 afterCompletion 里做资源清理
- **说「afterCompletion 只在正常流程执行」** —— 恰恰相反，它是异常时唯一还会被调用的回调，设计上接近 finally
- **说「@ResponseBody 的返回值还是要经过 ViewResolver」** —— 不会。`RequestResponseBodyMethodProcessor` 直接把对象经消息转换器写入响应，视图解析阶段被跳过
- **说「请求可以同时用两个 @RequestBody 取不同字段」** —— 请求体是一次性流，只能被读一次，一个方法里只能有一个 `@RequestBody`
- **认为 DispatcherServlet 直接反射调用 Controller** —— 中间隔着 HandlerAdapter；handler 类型不只有注解方法，还有 `Controller` 接口、`HttpRequestHandler` 等
- **说「异常会被 DispatcherServlet 直接抛给容器」** —— 先经过 `HandlerExceptionResolver` 链，`@ExceptionHandler` / `@ControllerAdvice` 命中的话根本不会抛出去
- **混淆 `@RequestParam` 和 `@ModelAttribute`** —— 前者只取单个参数，后者会按属性名绑定成对象并做类型转换、校验，还能把对象放进 Model

## 加分点

- 能准确说出 `DispatcherServlet` 的**九大组件**，并指出核心是 HandlerMapping、HandlerAdapter、HandlerExceptionResolver、ViewResolver
- 知道 `@RestController = @Controller + @ResponseBody`，因此 REST 接口的返回值走 `HttpMessageConverter` 而不是视图解析
- 能讲清 **HandlerAdapter 存在的意义是适配器模式**：让 DispatcherServlet 不必为每种 handler 类型写分支
- 知道 `HandlerExecutionChain` 是在 `getHandler` 阶段就组装好的，**拦截器不是调用时才找的**
- 能说明 **`afterCompletion` 只对 preHandle 返回过 true 的拦截器回调**，这是拦截器链的常规约定，很多面试者会答错
- 提到 **`@ExceptionHandler` 复用了 Controller 的参数解析和返回值处理基础设施**，所以两种方法的写法高度一致
- 提到 **ResponseEntity 走 `HttpEntityMethodProcessor`**，可以同时控制状态码、响应头和响应体，而不是只序列化 body
- 能说出 Spring 5.2 引入的**函数式端点**（`RouterFunction` / `RouterFunctionMapping`）是注解式之外的另一种路由方式
- 知道 `FlashMapManager` 的用途是**跨重定向传参**（重定向后仍能拿到一次性的 flash 属性），是九大组件里最容易被忽略的一个

## 版本差异

| 版本 | 差异 |
|---|---|
| Spring 4.x | `@RestController` 引入；`@ControllerAdvice` 支持 basePackages / assignableTypes 等限定范围 |
| Spring 5.0 | 引入 `ResponseStatusException`；WebFlux 提供函数式端点，MVC 侧后续跟进 |
| Spring 5.2 | MVC 引入 `RouterFunction` 支持函数式端点 |
| Spring 5.3 | `PathPatternParser` 可配置用于 MVC，但默认仍是 `AntPathMatcher` |
| Spring 6.0 | 路径匹配默认改用 **`PathPatternParser`**，替代 `AntPathMatcher`；迁移到 `jakarta.servlet.*`；`WebMvcConfigurerAdapter` 早已移除，统一用 `WebMvcConfigurer` |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: DispatcherServlet 找到 HandlerExecutionChain 之后，用什么来统一调用不同类型的 handler？
    options:
      A: ViewResolver
      B: HandlerAdapter
      C: HandlerExceptionResolver
      D: HandlerMethodArgumentResolver
    answer: B
    analysis: HandlerAdapter 通过 supports(handler) 判断能否适配，再统一用 handle(request, response, handler) 调用，从而屏蔽 HandlerMethod、Controller 接口、HttpRequestHandler 等类型差异，是适配器模式的应用。

  - type: JUDGE
    stem: 拦截器的 postHandle 方法在 handler 抛出异常时也会被执行。
    answer: F
    analysis: handler 抛异常时 ha.handle() 中断，applyPostHandle 不会被执行。此时 afterCompletion 仍会执行（前提是该拦截器 preHandle 返回过 true），所以资源清理应放在 afterCompletion。

  - type: MULTI
    stem: 关于 Filter 与 Interceptor，下列说法正确的有？
    options:
      A: Filter 由 Servlet 容器管理，执行在 DispatcherServlet 之前
      B: Interceptor 是 Spring Bean，可以注入 Service 等依赖
      C: Filter 可以包装 request/response，Interceptor 通常只能读取请求信息
      D: Interceptor 能拦截所有请求，包括没有匹配到 handler 的静态资源请求
    answer: ABC
    analysis: D 错误。Interceptor 只在 DispatcherServlet 内部、由 HandlerMapping 匹配到 handler 之后才会执行，未匹配到 handler 的请求不会走拦截器，会走 noHandlerFound 或静态资源处理。

  - type: CLOZE
    stem: |
      补全 doDispatch 中「调用前拦截」和「调用后拦截」的两个方法名：
      ```java
      if (!mappedHandler.apply{{1}}(processedRequest, response)) {
          return;
      }
      mv = ha.handle(processedRequest, response, mappedHandler.getHandler());
      mappedHandler.apply{{2}}(processedRequest, response, mv);
      ```
    blanks:
      - ["PreHandle"]
      - ["PostHandle"]
    analysis: applyPreHandle 在所有拦截器的 preHandle 都返回 true 才继续；任一返回 false 直接 return，不调用 handler。applyPostHandle 逆序执行 postHandle，但在 handler 抛异常时会被跳过。
    difficulty: 2
````

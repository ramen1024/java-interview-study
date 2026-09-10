---
slug: equals-hashcode-contract
title: equals 和 hashCode 有什么契约？为什么重写一个必须重写另一个？
module: java-basics
tags: [equals, hashCode, Object, 集合]
difficulty: 2
frequency: 3
related:
  - slug: hashmap-internals
    type: PREREQUISITE
  - slug: string-immutable-pool
    type: RELATED
  - slug: autoboxing-cache
    type: RELATED
---

## 电梯版回答

Object 的 equals 默认就是引用比较，也就是 `this == obj`；hashCode 是 native 方法，默认值与对象身份绑定，规范只要求同一个对象在一次执行过程中保持一致，并尽量让不同对象取到不同的值。两者的契约是单向的：两个对象 equals 相等，它们的 hashCode 必须相等；但 hashCode 相等，equals 不一定为真。所以只重写 equals 不重写 hashCode，会让两个逻辑上相等的对象算出不同的哈希值，在 HashMap 里落到不同的桶，既能同时存进去两份，get 又取不回来；只重写 hashCode 不重写 equals 则相反，相等判断仍然是引用比较，依然去不了重，只是白白增加了哈希冲突。乘子取 31 是因为它是奇素数、分布较好，而且 `31 * i` 可以被 JIT 优化成 `(i << 5) - i`。record 会自动基于所有组件生成 equals 和 hashCode，值语义的对象优先用它。

## 展开讲解

### Object 的默认实现

```java
public class Object {
    public native int hashCode();

    public boolean equals(Object obj) {
        return (this == obj);
    }
}
```

- `equals` 的默认实现就是 `this == obj`，比较的是引用（对象身份），不是内容。
- `hashCode` 是 native 方法。规范没有规定它怎么算，只要求：同一对象在一次执行中多次调用返回同一个值，且 equals 用到的信息没变时值也不能变，同时尽量让不相等的对象返回不同的值。HotSpot 的默认实现是按对象身份生成一个 identity hash 并缓存进对象头，常被概括成「由地址派生」，但两者并不是简单等同，更不要依赖它做逻辑判断。

### equals 的五条契约

`Object.equals` 的 javadoc 写得很清楚，实现必须满足：

1. **自反性**：对任何非 null 的 x，`x.equals(x)` 必须返回 true。
2. **对称性**：x.equals(y) 与 y.equals(x) 结果必须一致。
3. **传递性**：x.equals(y) 且 y.equals(z) 为真，则 x.equals(z) 也必须为真。
4. **一致性**：只要 equals 比较所用到的信息没有被修改，多次调用结果必须一致。
5. **与 null 比较**：对任何非 null 的 x，`x.equals(null)` 必须返回 false，而不是抛异常。

### hashCode 的三条契约

1. **一致性**：同一对象在一次执行中，只要 equals 所用信息没变，多次调用 `hashCode()` 必须返回同一个值。注意这不跨进程、跨 JVM 版本——两次运行结果不同是允许的。
2. **equals 相等则 hashCode 必须相等**：这是硬约束，违反会让基于哈希的集合（HashMap、HashSet、Hashtable、ConcurrentHashMap）出错。
3. **equals 不等不要求 hashCode 不等**：只要求「尽量不同」以改善散列表性能。这条是非强制的建议，因为对象数量可以无限，而 int 只有 2³² 个取值，冲突必然存在。

### 只重写一个会发生什么

先看不重写 hashCode 的版本：

```java
class Key {
    final String id;

    Key(String id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Key)) return false;
        return id.equals(((Key) o).id);
    }
    // 故意不重写 hashCode
}

Map<Key, String> map = new HashMap<>();
map.put(new Key("a"), "A");
map.put(new Key("a"), "B");                  // 本意是覆盖
System.out.println(map.size());              // 2，实际是新增
System.out.println(map.get(new Key("a")));   // null，取不回来
```

原因是 HashMap 的 put/get 是**两步**：先按 hashCode 定位桶，再在桶内用 equals 比对。只重写 equals 时两个逻辑相等的对象 hashCode 还是身份哈希，落到不同的桶，第二个 put 走的是一条空桶，直接新增；get 时又按新对象的身份哈希去另一个桶找，自然找不到。HashSet 同理，`add` 两次都返回 true，`remove` 也删不掉。

只重写 hashCode 不重写 equals 同样错，而且错得更隐蔽：两个逻辑相等的对象 hashCode 相同，会落到同一个桶，但桶内比较时 equals 仍是 `this == obj`，判定不相等，所以**依然存两份**，只是从「分在两个桶」变成「挤在同一个桶里」——既没修好去重，又制造了哈希冲突。

正确的写法是两者一起重写：

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Key key = (Key) o;
    return age == key.age && Objects.equals(id, key.id);
}

@Override
public int hashCode() {
    return Objects.hash(id, age);
}
```

### 为什么乘子取 31

手写 hashCode 的经典形式是逐字段累加：

```java
int result = 17;
result = 31 * result + name.hashCode();
result = 31 * result + age;
return result;
```

选 31 有三个理由：

- **31 是奇素数**。用偶数做乘子，乘法溢出时会丢掉低位信息（乘以 2 的幂等价于左移，低位补 0）；用合数则各字段之间的信息更容易混叠。素数与字段内容的关联性较小，冲突更少。
- **`31 * i` 可以被优化成 `(i << 5) - i`**，即左移 5 位减自身。这是 JIT 能做的强度削减，比一般整型乘法便宜。这是《Effective Java》给出的推荐理由之一。
- **有历史与实现上的先例**：`String.hashCode()` 用的就是 31，容易保持一致。注意这是实现经验，不是语言规范要求的乘子，换成别的奇素数编译也能过。

字段的 hashCode 要用 `Objects.hashCode(field)`（null 安全）或者基本类型的包装类的 hashCode/`Long.hashCode` 等，避免对 null 字段直接调方法。

### Objects 工具类与 record

- `Objects.equals(a, b)`：内部先判 `a == b`，再判 `a != null && a.equals(b)`，双 null 返回 true，null 与非 null 返回 false，省去手写 null 判断。
- `Objects.hash(...)`：基于 `Arrays.hashCode(Object[])`，等价于按 31 乘子累加，null 字段按 0 处理。缺点是每次调用会为可变参数创建一个数组并做自动装箱，极热路径上不如手写。
- **record**：编译器会基于所有组件自动生成 equals、hashCode 和 toString，语义就是「所有组件相等则对象相等」。所以不可变值对象优先用 record，既省代码又避免手写契约时踩坑。

## 追问链

### Q1: Object 默认的 equals 和 hashCode 分别是什么行为？契约要求哪些？

equals 默认返回 `this == obj`，是引用相等；hashCode 默认是 native 的身份哈希，与对象身份绑定。契约上，equals 要满足自反、对称、传递、一致、与 null 比较返回 false；hashCode 要满足同一次执行内一致，且 equals 相等时两者 hashCode 必须相等。

#### Q1.1: 契约为什么是单向的——equals 相等要求 hashCode 相等，反过来却不要求？

因为哈希表的查找是「先按 hashCode 缩小范围，再用 equals 确认」的两步结构。如果相等的对象 hashCode 不同，它们会被分到不同的桶，第一步就找错了地方，equals 根本没机会执行。反过来，int 只有 2³² 个取值而对象数量不限，哈希冲突不可避免，要求「hashCode 不等则 equals 必不等」在数学上做不到，实际中也不需要——冲突了在桶内用 equals 继续判断即可。

##### Q1.1.1: 如果让 hashCode 恒定返回 1，契约还成立吗？会发生什么？

契约成立：恒定值满足一致性，equals 相等的对象 hashCode 当然也相等。但所有对象都挤进同一个桶，HashMap 的查找从均摊 O(1) 退化成遍历；Java 8 中桶内元素超过阈值会树化成红黑树，把最坏情况从 O(n) 降到 O(log n)，但仍然远差于正常散列。这说明 hashCode 契约只保证**正确性**，性能靠的是「尽量让不相等的对象返回不同 hashCode」这条非强制建议。

###### Q1.1.1.1: 那 HashMap 对桶内长链表做了什么补救？

Java 8 引入红黑树：链表长度达到 `TREEIFY_THRESHOLD = 8` 且数组长度不小于 `MIN_TREEIFY_CAPACITY = 64` 时树化，退化阈值是 6。树化时如果 key 实现了 `Comparable` 就按它排序，否则用 `tieBreakOrder` 比较，保证树结构稳定。Java 7 没有树化，这种情况就是纯链表 O(n)。

### Q2: 只重写 equals 不重写 hashCode，具体在 HashMap 里怎么出错？

put 时先用 hashCode 定位桶：两个逻辑相等的对象 hashCode 不同，落到不同的桶，第二个对象被当作新 key 插入，size 变成 2；get 时又按查询对象的身份哈希去另一个桶找，返回 null。结果就是「存得进去两份、按相等语义却取不回来」，这是功能缺陷，不是性能问题。

#### Q2.1: 那反过来只重写 hashCode 不重写 equals 呢？

两个逻辑相等的对象 hashCode 相同，会落到同一个桶，但桶内比较时 equals 仍是引用比较，判定不相等，因此仍然会存两份，只是变成了同一桶内的链表冲突。也就是说 hashCode 只负责分桶，不负责判定相等，只重写它修不好去重。

##### Q2.1.1: HashMap 判断 key 相等时具体怎么比较？为什么先比 hash 再比 equals？

源码里是这样一个条件：

```java
if (p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k))))
```

先用 int 比 `p.hash == hash`，整型比较比方法调用便宜得多，能过滤掉绝大多数候选；hash 相同后再用 `==` 做一次引用快路径（同一个对象直接命中，省掉 equals 调用）；最后才落到 `key.equals(k)`。这也是为什么重写 equals 必须搭配 hashCode——hash 不对，后面的比较根本没机会走到。

##### Q2.1.2: 既然 hashCode 只是用来分桶，那 equals 能省掉吗？

不能。桶内可能有多个 hashCode 相同的不同对象（哈希冲突），必须靠 equals 才能确认哪个是目标 key。反过来，如果 equals 恒为 true、hashCode 也恒定，HashMap 会把所有 key 当成同一个，语义完全错乱。两者是「先粗筛、后精判」的分工。

### Q3: 为什么 hashCode 的乘子取 31？换成 37 行不行？

行，31 不是规范要求，只是历史与性能的折中。选它的理由：31 是奇素数，分布较好；`31 * i` 能被 JIT 优化成 `(i << 5) - i`，比一般乘法便宜；`String.hashCode` 也用了 31，容易保持一致。换成别的奇素数同样合法，最终看实测碰撞率。

#### Q3.1: hashCode 计算允许溢出吗？溢出会不会破坏契约？

允许。Java 的 int 乘法溢出按补码回绕，结果仍然是一个确定的 int，而 hashCode 契约只要求「同一次执行内保持一致、equals 相等则值相等」，并不要求不溢出。所以溢出不会破坏契约，这也是几乎所有手写 hashCode 都直接让 int 自然溢出的原因。要跨进程稳定（比如落库做分片键）时，得显式用 `Objects.hash` 的固定算法或自定义稳定算法，不能依赖默认实现。

##### Q3.1.1: 用可变字段参与 hashCode 会怎样？

会破坏哈希表的不变量。对象放进 HashSet/HashMap 后如果修改了参与 hashCode 的字段，它的 hashCode 就变了，再按新哈希去找原来的桶，`contains`/`remove` 都会失败，表现为「明明还在集合里却找不到」，也是常见的内存泄漏（对象实际无法被移除）。契约要求 equals 所用信息不变时 hashCode 不变，可变字段正好违反它。所以作为 HashMap key 的对象应当是不可变的，或者至少参与 equals/hashCode 的字段不可变。

### Q4: Objects.hash 和 record 帮我们做了什么？能完全替代手写吗？

`Objects.hash` 提供了 null 安全、写法简洁的通用实现，`Objects.equals` 解决了 null 判断；record 则由编译器基于所有组件生成 equals/hashCode/toString。它们能规避大部分手写错误，但 `Objects.hash` 有可变参数数组和自动装箱开销，超热路径仍建议手写或用工具生成并固定算法。

#### Q4.1: record 的 equals 和 hashCode 是怎么生成的？

编译器基于所有组件生成，Java 16 起底层通过 `java.lang.runtime.ObjectMethods` 的 bootstrap 方法（`invokedynamic`）实现，对基本类型和引用类型分别比较，数组组件会退化成 `Arrays.equals` / `Arrays.hashCode` 的语义。只要 record 的语义就是「所有组件决定相等」，用它比自己写更可靠——不可变、组件即状态，天然满足一致性与不可变性。

## 常见坑

- **「hashCode 就是对象的内存地址」** —— 规范从来没有这个约定。HotSpot 的默认实现是按对象身份生成并缓存到对象头，常被简化成「与地址相关」，但依赖它做任何逻辑判断都是错的，对象还可能被 GC 移动/压缩。
- **「equals 相等，hashCode 可以不相等」** —— 直接违反硬性契约，会让 HashMap/HashSet 失去去重和查找能力。
- **「hashCode 相等，equals 就必须相等」** —— 反了。哈希冲突允许存在，桶内还要用 equals 决断，int 只有 2³² 个取值而对象不限量，冲突必然发生。
- **「只重写 equals 就够了，hashCode 只是性能优化」** —— 不重写 hashCode 会导致同一个逻辑对象在 HashMap 里能存两份、get 取不回，是功能缺陷不是性能问题。
- **「x.equals(null) 抛 NPE 或返回 true 都可以」** —— 契约要求返回 false。用 `instanceof` 判断时 null 会自然返回 false，这也是它比 `getClass() != o.getClass()` 更省心的原因之一。
- **「用可变字段参与 equals/hashCode 没关系」** —— 对象进哈希表后再改字段，会导致 remove/get 失效，等于对象「丢」在集合里。
- **「子类用 instanceof 重写 equals 也一定能满足对称性」** —— 反例是经典的 Point（用 instanceof）与扩展它的 CounterPoint：`point.equals(counterPoint)` 为 true，反向却为 false，对称性被破坏。用 `getClass()` 能保证对称但放弃跨子类相等，两条路都有取舍。

## 加分点

- **能说出对称性破坏的完整反例**：父类 `equals` 用 `instanceof`、子类加了新状态，就会出现单向相等。Effective Java 的建议是「组合优于继承」，或者显式用 `getClass()` 比较并接受子类与父类不相等。
- **知道 HashMap 比较 key 的源码细节** `p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k)))`：先 hash 后 `==` 快路径再 equals，能解释为什么「hashCode 写坏了，equals 根本没机会被调」。
- **指出 Lombok/IDE 生成的差异**：`@EqualsAndHashCode` 默认用非静态非瞬态字段，是否调用父类实现由 `callSuper` 决定，漏了 `callSuper` 或字段范围选错时同样会违反契约；IDE 生成则要注意「是否包含继承字段」。
- **`Objects.hash` 的开销**：每次调用创建数组并装箱，可用 `Objects.hashCode`（单字段）或手写避免，在高频查找的 key 上是真实成本。
- **一致性契约与并发**：如果 equals 用到的字段被并发修改，结果就不可预期；这也是「HashMap 的 key 要可变性最小」的另一个理由。
- **跨进程一致性**：hashCode 不保证跨 JVM/跨版本稳定（例如 `String.hashCode` 虽然算法公开且被规范固定，但自定义实现不保证），需要稳定哈希时用显式算法。

## 版本差异

| 版本 | 差异 |
|---|---|
| Java 7 | 引入 `java.util.Objects`，带来 `Objects.equals` / `Objects.hash` / `Objects.hashCode`，此前只能手写 null 判断 |
| Java 14 / 15 | record 预览 |
| Java 16 | record 转正，编译器自动为所有组件生成 `equals` / `hashCode` / `toString`，底层经 `java.lang.runtime.ObjectMethods`（invokedynamic）实现 |

## 自测题

````yaml
questions:
  - type: CHOICE
    stem: 关于 equals 与 hashCode 的契约，下列说法正确的是？
    options:
      A: equals 相等的两个对象，hashCode 必须相等
      B: hashCode 相等的两个对象，equals 必须为 true
      C: equals 不相等时 hashCode 必须不同
      D: hashCode 只在调用 equals 时才会被使用
    answer: A
    analysis: "契约是单向的：equals 相等则 hashCode 必须相等；反之 hashCode 相等只说明可能相等，还要用 equals 判定。equals 不等时 hashCode 允许相同（哈希冲突）。"

  - type: JUDGE
    stem: 只重写 equals 而不重写 hashCode，两个逻辑相等的对象在 HashSet 中只会保留一份。
    answer: F
    analysis: 不重写 hashCode 时两者身份哈希不同，会落到不同的桶，HashSet.add 两次都成功，实际保留两份；remove 也删不掉。

  - type: MULTI
    stem: 只重写 hashCode 而不重写 equals，可能出现的后果有？
    options:
      A: 两个逻辑相等的对象在 HashMap 中仍会存成两份
      B: 两个逻辑相等的对象会落到同一个哈希桶
      C: 自动修复了去重问题
      D: 桶内元素增多，查询退化为遍历
    answer: ABD
    analysis: hashCode 只负责分桶，相等判定仍靠 equals；不重写 equals 时它还是引用比较，所以仍然去不了重，且相同哈希让它们挤在同一桶，反而制造冲突。

  - type: CLOZE
    stem: |
      补全下面手写的 hashCode 使用的乘子：
      ```java
      @Override
      public int hashCode() {
          int result = 17;
          result = {{1}} * result + name.hashCode();
          result = 31 * result + age;
          return result;
      }
      ```
    blanks:
      - ["31"]
    analysis: 31 是奇素数，分布较好，且 31 * i 可被 JIT 优化为 (i << 5) - i。这是《Effective Java》的推荐做法，并非语言规范要求。
    difficulty: 2
````

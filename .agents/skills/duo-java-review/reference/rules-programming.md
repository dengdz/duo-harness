# 编程规约规则细则

> 本文件包含阿里巴巴Java开发手册中编程规约的完整规则条目。
> 当审查代码涉及命名、常量、格式、OOP、集合、并发、控制语句、注释时加载本文件。

## 1.1 命名规约

| 编号 | 等级 | 规则 |
|------|------|------|
| N-01 | BLOCKER | 代码中的命名均不能以下划线或美元符号开始，也不能以下划线或美元符号结束 |
| N-02 | BLOCKER | 代码中的命名严禁使用拼音与英文混合的方式，更不允许直接使用中文的方式 |
| N-03 | BLOCKER | 类名使用UpperCamelCase风格，必须遵从驼峰形式（DO/DTO/VO/DAO/PO/BO等模型类后缀除外） |
| N-04 | BLOCKER | 方法名、参数名、成员变量、局部变量都统一使用lowerCamelCase风格，必须遵从驼峰形式 |
| N-05 | BLOCKER | 常量命名全部大写，单词间用下划线隔开，力求语义表达完整清楚，不要嫌名字长 |
| N-06 | BLOCKER | 抽象类命名使用Abstract或Base开头；异常类命名使用Exception结尾；测试类命名以它要测试的类的名称开始，以Test结尾 |
| N-07 | BLOCKER | 类型与中括号紧挨相连来表示数组（如`int[] arr`，而非`int arr[]`） |
| N-08 | BLOCKER | POJO类中布尔类型的变量，都不要加is前缀，否则在框架反向解析时会引起"序列化错误" |
| N-09 | BLOCKER | 包名统一使用小写，点分隔符之间有且仅有一个自然语义的英语单词。包名统一使用单数形式，类名如果有复数含义可以使用复数形式 |
| N-10 | BLOCKER | 避免在子父类的成员变量之间、不同代码块的局部变量之间采用完全相同的命名，使可理解性降低 |
| N-11 | BLOCKER | 杜绝完全不规范的缩写，避免望文不知义（如AbstractCondition缩写为AbsCond） |
| N-12 | CRITICAL | 为了达到代码自解释的目标，任何自定义编程元素在命名时，应尽量使用完整的单词组合来表达 |
| N-13 | CRITICAL | 在模块/接口/类/方法中如果使用了设计模式，在命名时体现出具体模式（如`OrderFactory`、`LoginProxy`） |
| N-14 | MAJOR | 接口类中的方法和属性不要加任何修饰符号（public也不要加），保持代码的简洁性 |
| N-15 | CRITICAL | 接口和实现类的命名规则：对于Service和DAO类，基于SOA理念，暴露出来的服务一定是接口类，内部的实现类用Impl后缀与接口区分；如果是形容能力的接口名称，取对应的形容词为接口名（如`Translatable`） |

## 1.2 常量定义

| 编号 | 等级 | 规则 |
|------|------|------|
| C-01 | BLOCKER | 不允许任何魔法值（即未经预先定义的常量）直接出现在代码中 |
| C-02 | BLOCKER | 在long或者Long赋值时，数值后使用大写字母L，不能是小写字母l（小写l容易和数字1混淆） |
| C-03 | CRITICAL | 不要使用一个常量类维护所有常量，要按常量功能进行归类，分开维护 |
| C-04 | CRITICAL | 常量的复用层次：跨应用共享常量放置在二方库中，应用内共享常量放置在一方库中，子工程内共享常量放置在当前子工程中 |
| C-05 | CRITICAL | 如果变量值仅在一个固定范围内变化用enum类型来定义 |

## 1.3 代码格式

| 编号 | 等级 | 规则 |
|------|------|------|
| F-01 | BLOCKER | 大括号的使用约定：如果是大括号内为空，则简洁地写成{}即可；如果是非空代码块则：左大括号前不换行，左大括号后换行，右大括号前换行，右大括号后还有else等代码则不换行，表示终止的右大括号后必须换行 |
| F-02 | BLOCKER | 左小括号和右边相邻字符之间不出现空格；右小括号和左边相邻字符之间也不出现空格；而左大括号前需要加空格 |
| F-03 | BLOCKER | if/for/while/switch/do等保留字与括号之间都必须加空格 |
| F-04 | BLOCKER | 任何二目、三目运算符的左右两边都需要加一个空格（包括赋值运算符=、逻辑运算符&&、加减乘除符号等） |
| F-05 | BLOCKER | 采用4个空格缩进，禁止使用Tab字符。如果使用Tab缩进，必须设置1个Tab为4个空格 |
| F-06 | BLOCKER | 注释的双斜线与注释内容之间有且仅有一个空格 |
| F-07 | BLOCKER | 方法参数在定义和传入时，多个参数逗号后面必须加空格 |
| F-08 | BLOCKER | IDE的text file encoding设置为UTF-8；IDE中文件的换行符使用Unix格式（LF） |
| F-09 | CRITICAL | 单行字符数限制不超过120个，超出需要换行。换行时第二行相对第一行缩进4个空格，从第三行开始不再继续缩进 |
| F-10 | CRITICAL | 方法体内的执行语句组、变量的定义语句组、不同的业务逻辑之间或者不同的语义之间插入一个空行；相同业务逻辑和语义之间不需要插入空行 |
| F-11 | MAJOR | 没有必要增加若干空格来使变量的赋值等号与上一行对应位置的等号对齐 |

## 1.4 OOP规约

| 编号 | 等级 | 规则 |
|------|------|------|
| O-01 | BLOCKER | 避免通过一个类的对象引用访问此类的静态变量或静态方法，无谓增加编译器解析成本，直接用类名来访问即可 |
| O-02 | BLOCKER | 所有的覆写方法，必须加@Override注解 |
| O-03 | BLOCKER | 相同参数类型，相同业务含义，才可以使用Java的可变参数，避免使用Object。可变参数必须放在参数列表最后（建议不用可变参数编程） |
| O-04 | BLOCKER | 外部正在调用或者二方库依赖的接口，不允许修改方法签名，避免对接口调用方产生影响。过时的接口方法需要加@Deprecated注解，并清晰地说明采用的新接口或新服务是什么 |
| O-05 | BLOCKER | 不能使用过时的类或方法 |
| O-06 | BLOCKER | Object的equals方法容易抛空指针异常，应使用常量或确定有值的对象来调用equals（如`"test".equals(param)`） |
| O-07 | BLOCKER | 所有整型包装类对象之间值的比较，全部使用equals方法比较 |
| O-08 | BLOCKER | 任何货币金额的存储和计算均以最小货币单位（如分）进行，使用long类型。若需精确小数计算，使用BigDecimal，严禁使用float/double |
| O-09 | BLOCKER | 定义数据对象DO类时，属性类型要与数据库字段类型相匹配（如数据库字段为bigint则DO属性为Long） |
| O-10 | BLOCKER | 构造方法里面禁止加入任何业务逻辑，如果有初始化逻辑，请放在init方法中 |
| O-11 | CRITICAL | POJO类必须要有toString方法。如果继承了另一个POJO类，注意在前面加super.toString |
| O-12 | CRITICAL | 当一个类有多个构造方法，或者多个同名方法，这些方法应该按顺序放置在一起，便于阅读 |
| O-13 | CRITICAL | 类内方法定义的顺序依次是：公有方法或保护方法 > 私有方法 > getter/setter方法 |
| O-14 | CRITICAL | setter方法中参数名称与类成员变量名称一致，使用this.成员名 = 参数名 |
| O-15 | BLOCKER | 循环体内，字符串的拼接方式，使用StringBuilder的append方法进行扩展 |
| O-16 | BLOCKER | final可以声明类、成员变量、方法、以及本地变量，以下情况使用final关键字：不允许被继承的类、不允许修改引用的域对象、不允许被重写的方法、不允许运行期间重新赋值的局部变量、避免上下文重复传递的变量使用final修饰 |
| O-17 | CRITICAL | 慎用Object的clone方法来拷贝对象，因为默认的clone方法只是浅拷贝 |
| O-18 | BLOCKER | 类成员与方法访问控制从严：如果不允许外部直接通过new来创建对象，构造方法必须是private；工具类不允许有public或default构造方法；类非static成员变量并且与子类共享，必须是protected；类非static成员变量并且仅在本类使用，必须是private；类static成员变量如果仅在本类使用，必须是private；若是static成员变量，考虑是否为final；类成员方法只供类内部调用，必须是private；类成员方法只对继承类公开，必须是protected |

## 1.5 集合处理

| 编号 | 等级 | 规则 |
|------|------|------|
| COL-01 | BLOCKER | 关于hashCode和equals的处理：只要重写equals，就必须重写hashCode；因为Set存储的是不重复对象，根据hashCode和equals进行判断，所以Set存储的对象必须重写这两个方法；如果自定义对象作为Map的键，那么必须重写hashCode和equals |
| COL-02 | BLOCKER | 判断所有集合内部的元素是否为空，使用isEmpty()方法，而不是size()==0的方式 |
| COL-03 | BLOCKER | 在使用java.util.stream.Collectors类的toMap()方法转为Map集合时，一定要使用含有参数类型为BinaryOperator、参数名为mergeFunction的方法（避免key重复时抛IllegalStateException异常） |
| COL-04 | BLOCKER | 在使用java.util.stream.Collectors类的toMap()方法转为Map集合时，一定要注意当value为null时会抛NPE异常 |
| COL-05 | BLOCKER | ArrayList的subList结果不可强转成ArrayList，否则会抛ClassCastException异常 |
| COL-06 | BLOCKER | 使用Map的方法keySet()/values()/entrySet()返回集合对象时，不可以对其进行添加元素操作，否则会抛UnsupportedOperationException异常 |
| COL-07 | BLOCKER | Collections类返回的对象（如emptyList/singletonList等）不可进行添加或删除元素操作，否则会抛UnsupportedOperationException异常 |
| COL-08 | BLOCKER | 使用集合转数组的方法，必须使用集合的toArray(T[] array)，传入的是类型完全一致、长度为0的空数组（如`list.toArray(new String[0])`） |
| COL-09 | BLOCKER | 在使用Collection接口任何实现类的addAll()方法时，都要对输入的集合参数进行NPE判断 |
| COL-10 | BLOCKER | 使用工具类Arrays.asList()把数组转换成集合时，不能使用其修改集合相关的方法（add/remove/clear方法会抛UnsupportedOperationException） |
| COL-11 | BLOCKER | 泛型通配符`<? extends T>`来接收返回的数据，此写法的泛型集合不能使用add方法，而`<? super T>`不能使用get方法；PECS（Producer Extends Consumer Super）原则：频繁往外读取内容适合用`<? extends T>`，经常往里插入的适合用`<? super T>` |
| COL-12 | BLOCKER | 不要在foreach循环里进行元素的remove/add操作。remove元素请用Iterator方式，如果并发操作，需要对Iterator对象加锁 |
| COL-13 | BLOCKER | 在JDK7及以上版本，Comparator实现类要满足自反性、传递性、对称性，不然Arrays.sort/Collections.sort会抛IllegalArgumentException异常 |
| COL-14 | CRITICAL | 集合初始化时，尽量指定集合初始值大小 |
| COL-15 | CRITICAL | 使用entrySet遍历Map类集合KV，而不是keySet方式遍历（keySet遍历需要两次查询，性能较差） |
| COL-16 | CRITICAL | 高度注意Map类集合K/V能不能存储null值的情况（Hashtable的K/V都不能为null；ConcurrentHashMap的K/V都不能为null；HashMap的K/V都可以为null） |
| COL-17 | BLOCKER | 合理利用集合的有序性(sort)和稳定性(order)，避免集合的无序性(unsort)和不稳定性(unorder）带来的负面影响（如HashMap是无序的，LinkedHashMap是有序的） |
| COL-18 | CRITICAL | 利用Set元素唯一的特性，可以快速对一个集合进行去重操作，避免使用List的contains方法进行遍历去重 |

## 1.6 并发处理

| 编号 | 等级 | 规则 |
|------|------|------|
| CON-01 | BLOCKER | 获取单例对象需要保证线程安全，其中的方法也要保证线程安全 |
| CON-02 | BLOCKER | 创建线程或线程池时请赋予有意义的业务名称，便于出错时排查 |
| CON-03 | BLOCKER | 线程资源必须通过线程池提供，不允许在应用中自行显式创建线程 |
| CON-04 | BLOCKER | 线程池不允许使用Executors去创建，而是通过ThreadPoolExecutor的方式创建，这样的处理方式更加明确线程池的运行规则，规避资源耗尽风险（Executors返回的线程池对象的弊端：FixedThreadPool和SingleThreadPool允许的请求队列长度为Integer.MAX_VALUE，可能会堆积大量的请求导致OOM；CachedThreadPool允许的创建线程数量为Integer.MAX_VALUE，可能会创建大量的线程导致OOM） |
| CON-05 | BLOCKER | SimpleDateFormat是线程不安全的类，一般不要定义为static变量，如果定义为static，必须加锁，或者使用DateUtils工具类。如果是JDK8以上，使用Instant代替Date，LocalDateTime代替Calendar，DateTimeFormatter代替SimpleDateFormat |
| CON-06 | BLOCKER | 必须回收自定义的ThreadLocal变量，尤其在线程池场景下，线程经常会被复用，如果不清理自定义的ThreadLocal数据，可能会影响后序业务逻辑和造成内存泄露等问题。尽量在代码的finally代码块中调用其remove方法 |
| CON-07 | BLOCKER | 在使用阻塞等待获取锁的方式中，必须在try代码块之外，并且在加锁方法与try代码块之间没有任何可能抛出异常的代码，避免在finally中unlock时出现异常 |
| CON-08 | BLOCKER | 在使用尝试机制获取锁的方式中，进入业务代码块之前，必须先判断当前线程是否持有锁。锁的释放规则与锁的阻塞等待方式相同 |
| CON-09 | BLOCKER | 并发修改同一记录时，避免更新丢失，需要加锁。要么在应用层加锁，要么在缓存加锁，要么在数据库层使用乐观锁，使用version作为更新依据 |
| CON-10 | BLOCKER | 多线程并行处理定时任务时，Timer运行多个TimeTask时，只要其中之一异常，其他任务便会自动终止运行。使用ScheduledExecutorService替代Timer |
| CON-11 | BLOCKER | 资金相关的金融计算，使用BigDecimal。禁止使用float/double直接进行运算 |
| CON-12 | BLOCKER | 使用CountDownLatch进行异步转同步操作时，必须保证countDown方法被调用在任何情况下都有执行机会，建议在finally中执行 |
| CON-13 | BLOCKER | 避免Random实例被多线程使用，虽然共享该实例是线程安全的，但会因竞争同一seed而导致性能下降。使用ThreadLocalRandom或Math.random() |
| CON-14 | BLOCKER | 在调用Object的wait/notify/notifyAll方法前，必须先获得该对象的锁（即在synchronized块内调用） |
| CON-15 | CRITICAL | HashMap在容量不够进行resize时由于高并发可能出现死链（死循环），导致CPU飙升。使用ConcurrentHashMap替代 |
| CON-16 | CRITICAL | volatile解决多线程内存不可见问题。对于一写多读，是可以解决变量同步问题；但是如果多写，同样无法解决线程安全问题 |
| CON-17 | CRITICAL | 双重检查锁定（Double-Check Locking）实现单例时，实例变量必须加volatile修饰（防止指令重排序导致返回未初始化的对象） |
| CON-18 | CRITICAL | ThreadLocal无法解决共享对象的更新问题，建议使用static修饰ThreadLocal变量 |
| CON-19 | CRITICAL | 在使用线程池时，对ThreadLocal对象的remove操作必须在finally中执行，防止内存泄漏 |
| CON-20 | BLOCKER | 定义线程池时，应该使用有界队列（如ArrayBlockingQueue或LinkedBlockingQueue指定容量），避免使用无界队列导致OOM |

## 1.7 控制语句

| 编号 | 等级 | 规则 |
|------|------|------|
| CTRL-01 | BLOCKER | 在一个switch块内，每个case要么通过break/return等来终止，要么注释说明case将继续往下执行，在一个switch块内，都必须包含一个default语句并且放在最后，即使它什么代码也没有 |
| CTRL-02 | BLOCKER | 当switch括号内的变量类型为String并且此变量为外部参数，必须先进行null判断 |
| CTRL-03 | BLOCKER | 在if/else/for/while/do语句中必须使用大括号。即使只有一行代码，避免采用单行的编码方式 |
| CTRL-04 | BLOCKER | 在表达式中，如果使用到括号，应尽量减少不必要的括号 |
| CTRL-05 | CRITICAL | 三目运算符condition ? 表达式1 : 表达式2中，表达式1和表达式2的类型必须一致，避免自动装箱/拆箱导致NPE |
| CTRL-06 | BLOCKER | 在高并发场景中，避免使用"等于"判断作为中断或退出的条件（浮点数比较应使用BigDecimal或指定误差范围） |
| CTRL-07 | CRITICAL | 除常用方法（如getXxx/isXxx）等外，不要在条件判断中执行其它复杂的逻辑，将复杂逻辑赋值给一个有意义的布尔变量名，以提高可读性 |
| CTRL-08 | CRITICAL | 循环体中的语句要考量性能，以下操作尽量移至循环体外处理：定义对象、变量；获取数据库连接；进行不必要的try-catch操作 |

## 1.8 注释规约

| 编号 | 等级 | 规则 |
|------|------|------|
| COM-01 | BLOCKER | 类、类属性、类方法的注释必须使用Javadoc规范，使用`/** */`格式，不得使用`// xxx`方式 |
| COM-02 | BLOCKER | 所有的抽象方法（包括接口中的方法）必须要用Javadoc注释、除了返回值、参数、异常说明外，还必须指出该方法做什么事情，实现什么功能 |
| COM-03 | BLOCKER | 所有的类都必须添加创建者和创建日期 |
| COM-04 | BLOCKER | 方法内部单行注释，在被注释语句上方另起一行，使用`//`注释。方法内部多行注释使用`/* */`注释，注意与代码对齐 |
| COM-05 | BLOCKER | 所有的枚举类型字段必须要有注释，说明每个数据项的用途 |
| COM-06 | CRITICAL | 与其"半吊子"英文来注释，不如用中文注释把问题说清楚。专有名词、关键字保持英文原文即可 |
| COM-07 | CRITICAL | 代码修改的同时，注释也要进行相应的修改，尤其是参数、返回值、异常、核心逻辑等的修改 |
| COM-08 | CRITICAL | 谨慎注释掉代码。在上方详细说明，而不是简单地注释掉。如果无用，则删除（代码版本管理可以追溯） |
| COM-09 | CRITICAL | 对于注释的要求：第一、能够准确反应设计思想和代码逻辑；第二、能够描述业务含义，使别的程序员能够迅速了解到代码背后的信息 |
| COM-10 | CRITICAL | 好的命名、代码结构是自解释的，注释力求精简准确、表达到位。避免出现注释的一个极端：过多过滥的注释 |
| COM-11 | CRITICAL | 特殊注释标记请注明标记人与标记时间。注意及时处理这些标记（如TODO标记、FIXME标记） |

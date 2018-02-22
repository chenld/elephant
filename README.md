# elephant

可靠消息系统，用于保证消息生产者生产的消息能可靠的发送出去，即本地事务执行成功，消息也一定要发送成功。

## 初衷

无论是SOA系统还是微服务系统，只要是分布式系统就会遇到分布式事务问题，目前业界解决分布式事务问题都是基于BASE理论，保证数据的最终一致性；具体的方案如补偿模式，可靠事件模式和TCC型事务等，而本系统就是基于可靠事件的一种实现。

## 原理图

### 实现原理

![image](https://github.com/yanghuijava/elephant/blob/master/screenshots/%E4%BA%8B%E5%8A%A1%E6%B6%88%E6%81%AF.png)

### 消息投递流程

![image](https://github.com/yanghuijava/elephant/blob/master/screenshots/%E5%8F%AF%E9%9D%A0%E6%B6%88%E6%81%AF%E6%8A%95%E9%80%921.png)

## 简介

* elephant-client：消息发送客户端
* elephant-common：公共代码
* elephant-example：示例代码
* elephant-mq：各种mq客户端都可以集成这这里，目前只支持activemq
* elephant-register：注册中心，基于zookeeper
* elephant-remoting：底层通信封装，基于netty4.x
* elephant-server：服务器端，接受消息代理，事务消息回查
* elephant-store：消息存储，目前基于mysql，可以扩展redis等
* elephant-springboot-amq-consumer：封装activemq的消费端

## 消息发送时序图

### 普通消息时序图

![image](https://github.com/yanghuijava/elephant/blob/master/screenshots/%E6%99%AE%E9%80%9A%E6%B6%88%E6%81%AF%E6%97%B6%E5%BA%8F%E5%9B%BE.png)

### 事务消息时序图

![image](https://github.com/yanghuijava/elephant/blob/master/screenshots/%E4%BA%8B%E5%8A%A1%E6%B6%88%E6%81%AF%E6%97%B6%E5%BA%8F%E5%9B%BE.png)

## 服务部署说明

### 环境

* JDK1.7+
* MySQL 5.0+
* zookeeper 3.4+
* activemq 5.x+

### 编译

下载源码，进入elephant文件夹，执行命令：

```shell
mvn clean install
```

会在elephant-server/target目录下生成elephant-server.tar.gz包

解压tar.gz包

### 安装

#### 1、创建名为elephant的数据库，执行sql目录下的t_message.sql文件
#### 2、修改配置文件，进入解压的目录elephant-server/config下
* application.properties
```
#server名称
spring.application.name=elephant-server
#server启动端口号
server.port=9999
#数据库url
spring.datasource.url=jdbc:mysql://localhost:3306/elephant?autoReconnect=true&useUnicode=true&characterEncoding=utf-8
#数据库账号
spring.datasource.username=root
#数据库密码
spring.datasource.password=root
#zookeeper地址
elephant.zk-server=localhost:2181
#activemq地址
elephant.mq.activemq-broker-url=failover://(tcp://localhost:61616)?initialReconnectDelay=100
```
* log4j2.xml：可以修改日志的输出路径

#### 3、启动/停止/重新启动

```
sh bin/service.sh start/stop/restart
```

## 开发说明

把编译好的elephant-client jar包安装到本地maven仓库，然后引入
```xml
<dependency>
    <groupId>com.yanghui.elephant</groupId>
    <artifactId>elephant-client</artifactId>
    <version>${编译好的版本号}</version>
</dependency>
```

具体使用请参照elephant-example里面的例子。

这里特别说明一下elephant-springboot-amq-consumer这个项目，我们知道activemq可以设置重试策略，当消费端消费消息失败的时候会根据重试策略进行重试，在一个队列中如果某个消息一直消费失败，那么这个队列后面的消息将会被阻塞，没法被消费，直到这个消息被acknowledge；为了解决这个问题，我引入重试队列概念，对消费端进行了封装，下面进行使用说明（基于springboot，普通的spring项目无法使用）：

properties文件参数配置：

```
elephant.mq.activemq-broker-url=amq的地址
elephant.mq.activemq-pool-max-connections=连接池大小
elephant.mq.redelivery-delay=重试的间隔时间ms
```

springboot启动类上加注解：

```java
@EnableElephantMQ
```

写消息监听类：

```java
@Component
public class MQListener {
	
	@QueueListener(name="yanghui.queue.test1")
	public void test1(Message message) throws Exception{
		BytesMessage bm = (BytesMessage)message;
		byte[] value = new byte[(int)bm.getBodyLength()];
		bm.readBytes(value);
		System.out.println(new String(value,"utf-8"));
	}
```
监听类必须交给spring容器管理，否则无效；方法必须加上注解（监听queue：@QueueListener，监听topic：@TopicListnener），加上注解的方法会启动一个监听线程。

注解参数说明：

* name：queue或者topic的名称。
* retryTimes：重试次数，默认3次，当某个消息达到这个重试次数时，消息将会进入重试队列，进入重试队列的消息将会被无限重试，除非消息被消费成功或者手动acknowledge。

备注：

* 重试次数没有被持久化，均是基于内存的统计，当服务器重新启动后，重试次数将会被重新开始统计。


## 建议

若您有任何建议，可以通过QQ或邮件反馈。

QQ：672859954

Email：672859954@qq.com

* 本系统暂时并未用于生成环境，目前阶段只用于学习只用。






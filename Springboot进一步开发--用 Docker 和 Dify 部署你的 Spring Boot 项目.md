如果你手上没有一个现成的Springboot项目，我这里的仓库中有现成的Springboot仓库

https://github.com/njuptemmm/iheima_springboot3_realization.git

这个是最基础的版本，其中没有添加任何的配置文件，你可以从0开始对于Docker进行配置工作

https://github.com/njuptemmm/iheima_springboot3_realization.git

这个是已经添加了DockersFile和app.env等相关配置的版本，你可以跳过中间很多有关于配置的工作直接开始使用

## Docker

### 0.Docker及其相关知识

#### Docker 的核心概念

- **镜像 (Image)**：一个包含应用程序及其所有依赖项（如代码、运行时环境、库、配置文件等）的模板。镜像是一个只读的文件，它定义了容器将如何运行。你可以从公共仓库（如 Docker Hub）下载现有的镜像，也可以用 **Dockerfile** 来自定义创建。
- **容器 (Container)**：一个由镜像创建出来的、可运行的实例。你可以把它看作是应用程序的一个独立、隔离的运行环境。每个容器都运行在自己的沙盒中，互不影响，并且可以被轻松地创建、启动、停止、删除。
- **Dockerfile**：一个文本文件，其中包含了一系列指令，用于自动构建 Docker 镜像。它定义了构建镜像的每一个步骤，比如从哪个基础镜像开始、复制哪些文件、安装哪些软件、以及启动容器时要运行什么命令。
- Docker 的流行是因为它解决了软件开发和部署中的一个常见问题：“在我的机器上能运行，但在服务器上就不行了！”。这通常是由于不同环境之间的配置差异造成的。

### 使用maven进行打包

（使用maven内置的命令可以将程序打包成一个JAR文件）

- 在根目录中
- 运行`mvn clean package`
  - 编译你的 Java 源代码。
  - 运行所有单元测试。
  - 将编译好的 `.class` 文件和项目资源文件打包成一个 **JAR 文件**。

### 安装Docker Desktop

就是最简单的下载和安装这个软件，可以直接使用各大平台/直接找官网

但是就是官网下载的安装包默认直接装在你的C盘，甚至不给你选择安装路径的机会，建议看文章的安装方法[基于Docker+DeepSeek+Dify ：搭建企业级本地私有化知识库超详细教程 - 但风偏偏，雨渐渐 - 博客园](https://www.cnblogs.com/LaiYun/p/18808736)

其中最重要的一部分是docker engine进行配置工作，在里面添加各种镜像源。这个步骤是为后面拉去Dify所需镜像源的时候需要的。建议有多少塞多少进去，后面Dify拉取配置的时候越多镜像源最后越容易成功（？）

![image-20250814095948922](C:\Users\23816\AppData\Roaming\Typora\typora-user-images\image-20250814095948922.png)

客户以在Docker Engine中加上一堆镜像源




### 1. 编写 Dockerfile



Dockerfile 是一个文本文件，包含了构建 Docker 镜像所需的所有指令。以下是一个典型的 Spring Boot 项目的 Dockerfile 示例：

Dockerfile（这里是创建一个Dockerfile，让你保存在你springboot的根目录中，这样后面使用docker进行打包相关操作的时候可以直接运行相关的程序，完成打包）



```bash
# 基础镜像，通常使用 Java 的官方镜像->这里主要就看看你开发时候使用的java版本是什么样的
FROM openjdk:17-jdk-slim

# 暴露 Spring Boot 应用的端口，默认为 8080
EXPOSE 8080

# 在容器中创建 /app 目录
WORKDIR /app

# 将本地的 Spring Boot jar 包复制到容器的 /app 目录并重命名为 app.jar
# 'target/your-app-name.jar' 是您本地项目打包后的路径->注意前面的这个是一个路径，于是中间不能用空格，同时后面的/app/app.jar是目标路径，指定了文件在 Docker 镜像内部的位置和名称
COPY target/your-app-name.jar /app/app.jar

# 定义容器启动时执行的命令
# java -jar /app/app.jar 会启动您的 Spring Boot 应用
#其实也就对应我上一步中将打包完成的/app/app.jar进行复制到下一步，进行容器的执行
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

在编写 Dockerfile 时，请确保：

- **`FROM`**：选择一个合适的 **基础镜像**。`openjdk:17-jdk-slim` 是一个轻量级的选择，非常适合生产环境。
- **`EXPOSE`**：指定您的 Spring Boot 应用程序监听的端口。默认情况下，Spring Boot 应用通常在 **8080** 端口运行。也就我们
- **`COPY`**：确保 `target/your-app-name.jar` 的路径是正确的，它指向您打包好的可执行 JAR 文件。
- **`ENTRYPOINT`**：这是容器启动时要执行的命令。`java -jar /app/app.jar` 会运行您的应用程序。

再运行第一步的时候有时会卡在下载openjdk:17-jdk-slim这一步中，具体的表现是在下载的出现超时，提示连接不上相关网站

```bash
docker pull openjdk:17-jdk-slim
```

此时我们可以直接从网站中下载对应的依赖（也就是我上面使用的这个命令）

->这个主要是发生在下一步构建镜像的时候出现问题；



### 2. 构建 Docker 镜像



我们需要得知的是需要电脑本身需要先启动docker desktop才能够和docker网络进行连接，才能在命令行引用docker相关的库进行处理

在您的 Spring Boot 项目根目录下，使用以下命令来构建 Docker 镜像：

Bash

```
docker build -t your-image-name .
```

- **`docker build`** 是构建镜像的命令。
- **`-t your-image-name`** 为您的镜像指定一个 **名称**（tag）。例如：`my-springboot-app:1.0`。这个名称就是你使用过程中
- **`.`** (点号) 表示 Dockerfile 位于当前目录下。

这个命令会读取您的 Dockerfile，并按照其中的指令来创建一个新的 Docker 镜像。

其中如果出现卡在某个文件安装不下来，请参考第一步的解决方法。



### 3. 运行 Docker 容器

首先我们先要进行了解是的docker镜像和docker容器之间的关系：

**Docker 镜像**可以比喻为**一个模具或一个类（class）**。它是一个只读的模板，包含了运行一个应用所需的所有文件、代码、运行时环境、库文件和依赖项。镜像本身是静态的、不可变的。

**Docker 容器**可以比喻为**用这个模具生产出来的产品，或者由这个类创建出来的实例（instance）**。容器是镜像的一个运行实例，它是一个可读写的运行环境。你可以启动、停止、删除或重启一个容器。

**核心关系总结：**

- **镜像是容器的模板**：容器是基于镜像创建和运行的。
- **镜像本身不可变，容器可变**：镜像是一个只读层，容器则在镜像的基础上添加了一个可写层，所有的修改都发生在这个可写层上。
- **多容器可共享同一镜像**：你可以从同一个镜像创建和运行多个独立的容器。每个容器都拥有自己的文件系统、进程空间和网络接口，彼此之间相互隔离。

**意义**

- 镜像中打包了环境，于是在不同电脑中不用进行额外配置即可完成相关的操作
- 不同的容器共享同一个镜像，每一个容器都是互相独立的
- 打包成镜像之后，之后可以使用`docker pull`直接下载并且运行对应的应用，不用关心使用的变成语言和编程框架



在完成一个镜像的设置之后，还需要为这个镜像设置一个容器，我们运行的时候需要指定这个容器的名字

```
docker run -d -p 8080:8080 --name my-springboot-container your-image-name
```

- **`docker run`** 用于启动一个新容器。
- **`-d`** 让容器在 **后台** 运行（"detached" mode）。
- **`-p 8080:8080`** 是 **端口映射**。它将主机的 **8080** 端口映射到容器的 **8080** 端口。这样您就可以通过 `http://localhost:8080` 访问您的应用程序。（这个命令确定了打包完成的image的端口：）
- **`--name my-springboot-container`** 为容器指定一个易于识别的 **名称**。
- **`your-image-name`** 是您在第二步中构建的镜像名称。

**docker管理命令**：`docker images`（查看本地所有镜像），`docker ps`（查看正在运行的容器），`docker stop/start/rm`（停止/启动/删除容器），`docker rmi`（删除镜像）。

返回长串16进制数据，表示这个容器的ID（但是说你有安装好docker desktop你就能在运行的container名字看到这个容器ID，里面也有相关的日志信息）

同时我们要注意就是我们在程序实现（特别是像springboot项目）我们往往会将APIKEY的值存储在环境变量中`${API_KEY}`这部分的信息解决方式主要是在运行的时候进行处理：

```
docker run -d -p 8080:8080 --name container_name -e API_KEY="$env:API_KEY" image_name
```

能够将APIKEY传输到这个容器中，进行运算。



### 4. 验证



Spring Boot 应用程序应该已经在 Docker 容器中运行了。您可以通过以下方法进行验证：

- **浏览器访问**：打开浏览器并访问 `http://localhost:8080`，查看您的应用程序是否正常响应。

- **查看容器日志**：使用 `docker logs` 命令查看容器的输出日志，检查是否有异常：

  ```
  docker logs my-springboot-container(你在第三步设定的名字)
  ```

- **查看运行中的容器**：使用 `docker ps` 命令，您应该能看到您的 Spring Boot 容器正在运行。

查看日志之后可以发现和idea中相关的日志是类似的，但是与IDEA中相关的运行是有本质的差别：这个过程中使用的部署在docker的镜像运行的，而不是直接运行相关的程序。





## Dify

- 对于DIfy本身，这是一个封装了大模型API交互、管理知识库、设计应用界面的AI应用生产工厂。
- 我们在已经完成Springboot中程序设计的前提之下继续使用Dify，那么Dify能够将你实现的相关功能封装成一个工具，这样大模型就可以在需要的时候进行调用。

### 安装相关配置的工作

#### 克隆官方的项目

`git clone https://github.com/langgenius/dify.git`

直接在Github上寻找官方的仓库，复制URL之后直接拉取到需要下载的位置

#### 配置环境变量

主要就是要对于.env.example文件，然后重命名一下改成.env

原来在这里是有很多的攻略都在说要对于相关内容进行配置但是实际上删除example就可以了，最开始使用没有必要大幅度的修改，并且没有这么做完全无法完成其他的工作

#### 拉取得Dify依赖的镜像资源

直接在对应cmd中输入`docker-compose up -d`就开始进行下载。这个下载主要就是依靠你前面在docker文件夹中配置的各种镜像源

这一步出问题的概率很高，很容易干一半就炸了。就算是正常安装基本上也要个十来分钟（不知道是不是我这里网速不太行）

这里是有一种解决方法个人比较推荐：使用**阿里云容器镜像服务**，免费还好使。

**配置方法：**

1. 登录 [阿里云官网](https://www.aliyun.com/)，进入“容器镜像服务”控制台。
2. 在左侧导航栏选择“镜像加速器”，即可获取您的专属加速器地址，格式为 `https://<your_unique_id>.mirror.aliyuncs.com`。
3. 将此地址配置到 Docker Desktop 或 Linux 的 `/etc/docker/daemon.json` 文件中。（其实有下载docker desktop的可以直接配置到docker engine中）



### 将Springboot文件上传成为Dify的工具

这个是我们最后的目标了，就是要成为成为Tool之后在实际的开发过程中是哟ing

#### 确认你的springboot的镜像是否已经在container中运行

`docker run -d -p 8080:8080 --name container_name -e API_KEY="$env:API_KEY" image_name`

第一次我是使用上面的命令将APIKEY作为环境变量添加到image运行的container中

后面使用的时候直接启动对应的容器就可以了

`docker start my-app-container`

也可以在Docker Desktop中直接进行运行

![image-20250814095829569](C:\Users\23816\AppData\Roaming\Typora\typora-user-images\image-20250814095829569.png)

#### 启动Dify服务

在Docker文件输入指令

```
docker compose up -d
```

和前面一样直接使用Docker进行启动（我们这个相当于是使用docker compose在本地上完成了有关于Dify的配置所以说我们可以在本地进行运行）

访问http://127.0.0.1/直接进入Dify界面

#### 配置工具

[工具 - Dify](http://127.0.0.1/tools)直接进入本机的工具配置界面

选择创造自定义工具就是创建工具的地方

之后关键是对于Tool配置（主要就是对于Schema的配置工作）

**建议：对于api端口寻找都不熟练的小白**

下载安装一个apifox，然后再你的idea中安装apifox相关的插件，你可以按照教程将你的springboot项目的端口上传到apifox中。此时再在这个基础之上继续来配置URL。（主要apifox的自动化还是很不错的：能够分析你实现的不同功能，你平常项目管理好了话端口能直接传输到apifox中能够帮你整理完成）

之后就是编写Schema表了，下面是我配置地表，记得改一改其中的URL地址就可以了

```
{
  "openapi": "3.1.0",
  "info": {
    "title": "ai-chat",
    "description": "It's a study about the chat of Dify",
    "version": "v1.0.0"
  },
  "servers": [
    {
      "url": "http://192.168.91.1:8080"#URL地址，记得更改成你的主机的IP地址
    }
  ],
  "paths": {
    "/ai/chat": {
      "post": {
        "summary": "Send a message to the chat API",
        "description": "This endpoint allows users to send a message and get a response from the chat service.",
        "operationId": "ChatWithAI",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "properties": {
                  "prompt": {
                    "type": "string",
                    "description": "The message to be sent to the chat API."
                  },
                  "chatId": {
                    "type": "string",
                    "description": "The ID of the current chat session."
                  }
                },
                "required": ["prompt", "chatId"]
              }
            }
          }
        },
        "responses": {
          "200": {
            "description": "Successful response with chat output.",
            "content": {
              "application/json": {
                "schema": {
                  "type": "object",
                  "properties": {
                    "response": {
                      "type": "string"
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {}
  }
}
```

完成配置之后测试一下能否进行测试操作，能够测试完成你的代码就能够实现各种AI应用的开发操作了。

之后可以使用工作流完成相关的设置，在工作流中对于输入输出的数据进行设置，就能够使用工具完成各种操作。我们成功将一个Springboot项目转化成了一个在实际开发中能够使用到的工具辅助我们进行进一步地开发。



这个文章算是最近各种学习的点滴，也算是我最近研究的成果。

看看最后的情况怎么样吧，如果数据好了话我就来做做一个更加完善的版本。

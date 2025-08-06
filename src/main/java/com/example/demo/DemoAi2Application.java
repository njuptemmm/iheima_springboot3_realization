package com.example.demo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.example.demo.mapper") // 扫描MyBatis的Mapper接口,也就是扫描其中有关于数据库的部分
@SpringBootApplication
public class DemoAi2Application {

	public static void main(String[] args) {
		SpringApplication.run(DemoAi2Application.class, args);
	}

}

package com.example.demo;

import com.example.demo.utils.VectorDistanceUtils;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.ai.document.Document;
import java.util.Arrays;
import java.util.List;

//这里是用测试这个embed的功能是否正常的小部分
//但是就是一直显示连接出现问题
//但是照理来说这个是一个小的测验不知道为什么一直没法测试出来
@SpringBootTest
class DemoAi2ApplicationTests {

	@Autowired
	private OpenAiEmbeddingModel embeddingModel;

	@Autowired
	private VectorStore vectorStore;

	 @Test
	 public void testVectorStore() {

		 Resource resource = new FileSystemResource("database_study.pdf");

		 //1、创建pdf的读取器
		 PagePdfDocumentReader reader = new PagePdfDocumentReader(
				 resource, // 文件源
				 PdfDocumentReaderConfig.builder()
						 .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())//TextFormatter文本的格式化器，去除页眉页脚等，此处是默认
						 .withPagesPerDocument(1) // 每1页PDF作为一个Document
						 .build()
		 );
		 //2、读取pdf文档，拆分为Document
		 List<Document> documents = reader.read();
		 //3、写入向量库
		 vectorStore.add(documents);
		 //4、搜索
		 SearchRequest request= SearchRequest.builder()
				 .query("请你指出聚合数据查询中聚合函数COUNT(*)的作用") // 查询内容
				 .topK(5) // 返回前5个最相似的结果
				 .similarityThreshold(0.5f)// 相似度阈值，低于此值的结果将被过滤掉
				 .filterExpression("file_name=='database_study.pdf'") // 过滤条件，确保只搜索特定文件
				 .build();
		 List<Document> docs = vectorStore.similaritySearch("请你指出聚合数据查询中聚合函数COUNT(*)的作用");
		 if (docs == null) {
			System.out.println("没有搜索到任何内容");
			return;
		 }
		 for (Document doc : docs) {
			 System.out.println(doc.getId());
			 System.out.println(doc.getScore());
			 System.out.println(doc.getText());
		 }
	 }

	//这个是一个测试的功能，如果能够成功调用embed就能够实现对于信息的向量化
	@Test
	void contextLoads() {
		// 1.测试数据
		// 1.1.用来查询的文本，国际冲突
		String query = "global conflicts";

		// 1.2.用来做比较的文本
		String[] texts = new String[]{
				"哈马斯称加沙下阶段停火谈判仍在进行 以方尚未做出承诺",
				"土耳其、芬兰、瑞典与北约代表将继续就瑞典“入约”问题进行谈判",
				"日本航空基地水井中检测出有机氟化物超标",
				"国家游泳中心（水立方）：恢复游泳、嬉水乐园等水上项目运营",
				"我国首次在空间站开展舱外辐射生物学暴露实验",
		};
		// 2.向量化
		// 2.1.先将查询文本向量化
		float[] queryVector = embeddingModel.embed(query);

		// 2.2.再将比较文本向量化，放到一个数组
		List<float[]> textVectors = embeddingModel.embed(Arrays.asList(texts));

		// 3.比较欧氏距离
		// 3.1.把查询文本自己与自己比较，肯定是相似度最高的
		System.out.println(VectorDistanceUtils.euclideanDistance(queryVector, queryVector));
		// 3.2.把查询文本与其它文本比较
		for (float[] textVector : textVectors) {
			System.out.println(VectorDistanceUtils.euclideanDistance(queryVector, textVector));
		}
		System.out.println("------------------");

		// 4.比较余弦距离
		// 4.1.把查询文本自己与自己比较，肯定是相似度最高的
		System.out.println(VectorDistanceUtils.cosineDistance(queryVector, queryVector));
		// 4.2.把查询文本与其它文本比较
		for (float[] textVector : textVectors) {
			System.out.println(VectorDistanceUtils.cosineDistance(queryVector, textVector));
		}
	}




}

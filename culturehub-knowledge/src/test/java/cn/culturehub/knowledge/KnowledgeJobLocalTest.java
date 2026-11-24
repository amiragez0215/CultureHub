package cn.culturehub.knowledge;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.minio.*;
/** Real MySQL transaction/version fencing. Object storage and vector writes are controlled doubles. */
@EnabledIfSystemProperty(named="culturehub.localTest",matches="true")
class KnowledgeJobLocalTest {
 JdbcTemplate db;KnowledgeJobs jobs;MinioClient objects;VectorStore vectors;ObjectMapper json;Channel channel;
 @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{
  var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/knowledge_db?connectionTimeZone=Asia/Shanghai","root","");
  var migration=new java.io.File("../deploy/migrations/20261111-v3-knowledge.sql");
  new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(source);
  db=new JdbcTemplate(source);objects=mock(MinioClient.class);vectors=mock(VectorStore.class);json=new ObjectMapper().findAndRegisterModules();channel=mock(Channel.class);
  ObjectProvider<VectorStore> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(vectors);
  jobs=new KnowledgeJobs(db,new DataSourceTransactionManager(source),mock(cn.culturehub.RabbitTransport.class),json,objects,provider,"test");
  byte[] content="Public venue rules. Children under 12 need an adult guardian.".getBytes(StandardCharsets.UTF_8);
  when(objects.getObject(any(GetObjectArgs.class))).thenAnswer(i->new GetObjectResponse(okhttp3.Headers.of(),"bucket","region","object",new ByteArrayInputStream(content)));
 }
 @AfterEach void stop(){if(jobs!=null)jobs.stop();}
 String fixture(){String id=UUID.randomUUID().toString();db.update("insert into knowledge_document(id,venue_id,scope,visibility,knowledge_type,source,title,created_by) values(?,12,'VENUE','PUBLIC','AGE_RULE','official-fixture','rules',1)",id);return id;}
 String job(String id,int version){String job=UUID.randomUUID().toString();byte[] content="Public venue rules. Children under 12 need an adult guardian.".getBytes(StandardCharsets.UTF_8);
  db.update("insert into knowledge_document_version(document_id,version,object_key,file_type,sha256) values(?,?,'fixture','txt',?)",id,version,KnowledgeDocuments.hexDigest(content));
  db.update("update knowledge_document set latest_version=? where id=?",version,id);
  db.update("insert into ingestion_job(id,document_id,version,operation,status) values(?,?,?,'INDEX','QUEUED')",job,id,version);return job;
 }
 void consume(String job)throws Exception{var p=new MessageProperties();p.setDeliveryTag(17);jobs.consume(new Message(json.writeValueAsBytes(Map.of("jobId",job,"schemaVersion",1,"messageType",KnowledgeConfiguration.ROUTE)),p),channel);}
 @Test void failedNewVectorVersionKeepsOldVersionAndSuccessfulRedeliveryDoesNotRepeat()throws Exception{
  String id=fixture();String first=job(id,1);consume(first);
  assertEquals(1,db.queryForObject("select active_version from knowledge_document where id=?",Integer.class,id));
  consume(first);verify(vectors,times(1)).add(anyList());
  doThrow(new IllegalStateException("controlled embedding failure")).when(vectors).add(anyList());
  String second=job(id,2);consume(second);
  assertEquals(1,db.queryForObject("select active_version from knowledge_document where id=?",Integer.class,id));
  assertEquals("RETRY_WAIT",db.queryForObject("select status from ingestion_job where id=?",String.class,second));
  verify(channel,times(3)).basicAck(17,false);
 }
 @Test void deletionBeforeProcessingCannotReactivateDocument()throws Exception{
  String id=fixture();String first=job(id,1);db.update("update knowledge_document set deleted=true,active_version=null where id=?",id);consume(first);
  assertNull(db.queryForMap("select active_version from knowledge_document where id=?",id).get("active_version"));
  assertEquals("SUCCEEDED",db.queryForObject("select status from ingestion_job where id=?",String.class,first));
  verifyNoInteractions(objects,vectors);
 }
}

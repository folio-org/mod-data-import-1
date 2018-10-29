package org.folio.rest;


import com.jayway.restassured.RestAssured;
import com.jayway.restassured.builder.RequestSpecBuilder;
import com.jayway.restassured.http.ContentType;
import com.jayway.restassured.specification.RequestSpecification;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.http.HttpStatus;
import org.folio.rest.client.TenantClient;
import org.folio.rest.jaxrs.model.UploadDefinition;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(VertxUnitRunner.class)
public class RestVerticleTest {

  private static final String TENANT = "diku";
  private static final String DEFINITION_PATH = "/data-import/upload/definition";
  private static final String FILE_PATH = "/data-import/upload/file";
  private static final Logger LOG = LoggerFactory.getLogger("mod-data-import-test");

  private static Vertx vertx;
  private static int port;
  private static RequestSpecification spec;
  private static RequestSpecification specUpload;

  private static JsonObject file1 = new JsonObject()
    .put("name", "bib.mrc");
  private static JsonObject uploadDef1 = new JsonObject()
    .put("metaJobExecutionId", UUID.randomUUID().toString())
    .put("status", "NEW")
    .put("files", new JsonArray().add(file1));
  private static JsonObject uploadDef2 = new JsonObject()
    .put("metaJobExecutionId", UUID.randomUUID().toString())
    .put("id", UUID.randomUUID().toString())
    .put("files", new JsonArray().add(file1));
  private static JsonObject uploadDef3 = new JsonObject()
    .put("metaJobExecutionId", UUID.randomUUID().toString())
    .put("id", UUID.randomUUID().toString())
    .put("files", new JsonArray().add(file1));

  @BeforeClass
  public static void setUpClass(final TestContext context) throws Exception {
    Async async = context.async();
    vertx = Vertx.vertx();
    port = NetworkUtils.nextFreePort();

    String useExternalDatabase = System.getProperty(
      "org.folio.password.validator.test.database",
      "embedded");

    switch (useExternalDatabase) {
      case "environment":
        System.out.println("Using environment settings");
        break;
      case "external":
        String postgresConfigPath = System.getProperty(
          "org.folio.data.import.test.config",
          "/postgres-conf-local.json");
        PostgresClient.setConfigFilePath(postgresConfigPath);
        break;
      case "embedded":
        PostgresClient.setIsEmbedded(true);
        PostgresClient.getInstance(vertx).startEmbeddedPostgres();
        break;
      default:
        String message = "No understood database choice made." +
          "Please set org.folio.data.import.test.database" +
          "to 'external', 'environment' or 'embedded'";
        throw new Exception(message);
    }

    TenantClient tenantClient = new TenantClient("localhost", port, "diku", "dummy-token");
    DeploymentOptions restVerticleDeploymentOptions = new DeploymentOptions()
      .setConfig(new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class.getName(), restVerticleDeploymentOptions, res -> {
      try {
        tenantClient.postTenant(null, res2 -> {
          PostgresClient.getInstance(vertx, "diku")
            .save("uploadDefinition", uploadDef2.getString("id"), uploadDef2.mapTo(UploadDefinition.class), h -> {
            PostgresClient.getInstance(vertx, "diku")
              .save("uploadDefinition", uploadDef3.getString("id"), uploadDef3.mapTo(UploadDefinition.class), h2 -> {
              async.complete();
            });
          });
        });
      } catch (Exception e) {
        e.printStackTrace();
      }
    });

    spec = new RequestSpecBuilder()
      .setContentType(ContentType.JSON)
      .setBaseUri("http://localhost:" + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .build();

    specUpload = new RequestSpecBuilder()
      .setContentType("multipart/form-data")
      .setBaseUri("http://localhost:" + port)
      .addHeader(RestVerticle.OKAPI_HEADER_TENANT, TENANT)
      .build();
  }

  @Test
  public void uploadDefinitionCreate() {
    RestAssured.given()
      .spec(spec)
      .body(uploadDef1.encode())
      .when()
      .post(DEFINITION_PATH)
      .then()
      .statusCode(HttpStatus.SC_CREATED);
  }

  @Test
  public void uploadDefinitionGet() {
    RestAssured.given()
      .spec(spec)
      .when()
      .get(DEFINITION_PATH)
      .then()
      .statusCode(HttpStatus.SC_OK);
  }

  @Test
  public void uploadDefinitionGetById() {
    RestAssured.given()
      .spec(spec)
      .when()
      .get(DEFINITION_PATH + "/" + uploadDef2.getString("id"))
      .then()
      .statusCode(HttpStatus.SC_OK);
  }

  @Test
  public void uploadDefinitionUpdate() {
    RestAssured.given()
      .spec(spec)
      .body(uploadDef3.encode())
      .when()
      .put(DEFINITION_PATH + "/" + uploadDef3.getString("id"))
      .then()
      .statusCode(HttpStatus.SC_OK);
  }

  //@Test TODO add support of file storing
  public void fileUpload() {
    RestAssured.given()
      .spec(specUpload)
      .when()
      .post(FILE_PATH + "?jobExecutionId=" + UUID.randomUUID().toString() + "&uploadDefinitionId=" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_CREATED);
  }

  @Test
  public void fileDelete() {
    RestAssured.given()
      .spec(spec)
      .when()
      .delete(FILE_PATH + "/" + UUID.randomUUID().toString())
      .then()
      .statusCode(HttpStatus.SC_NO_CONTENT);
  }
}

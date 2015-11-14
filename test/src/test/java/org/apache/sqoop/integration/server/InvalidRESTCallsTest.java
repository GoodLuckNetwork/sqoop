/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sqoop.integration.server;

import org.apache.log4j.Logger;
import com.google.common.collect.Iterables;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.security.token.delegation.web.DelegationTokenAuthenticatedURL;
import org.apache.sqoop.model.MConfigList;
import org.apache.sqoop.model.MLink;
import org.apache.sqoop.test.infrastructure.Infrastructure;
import org.apache.sqoop.test.infrastructure.SqoopTestCase;
import org.apache.sqoop.test.infrastructure.providers.HadoopInfrastructureProvider;
import org.apache.sqoop.test.infrastructure.providers.SqoopInfrastructureProvider;
import org.apache.sqoop.test.utils.ParametrizedUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Infrastructure(dependencies = {HadoopInfrastructureProvider.class, SqoopInfrastructureProvider.class})
public class InvalidRESTCallsTest extends SqoopTestCase {

  private static final Logger LOG = Logger.getLogger(InvalidRESTCallsTest.class);

  // Validate returned response from server
  public static abstract class Validator {

    // Persisted connection object that we're testing against
    HttpURLConnection connection;

    // Persisted data from connection
    String error;
    String input;

    public void setConnection(HttpURLConnection connection) throws Exception {
      this.connection = connection;

      this.input = "";
      try {
        this.input = (connection.getInputStream() != null) ? IOUtils.toString(connection.getInputStream()) : "";
      } catch(Exception e) {
        // We're ignoring exception here because that means that request wasn't successful and data are in "error" stream
      }
      this.error = connection.getErrorStream() != null ? IOUtils.toString(connection.getErrorStream()) : "";
    }

    // Each test should implement whatever is needed here
    abstract void validate() throws Exception;

    // Verify HTTP response code
    public void assertResponseCode(int expected) throws Exception {
      assertEquals(connection.getResponseCode(), expected);
    }

    // Assert given exception from server
    public void assertServerException(String errorClass, String errorCode) throws Exception {
      // On exception, the error trace can't be null
      assertNotNull(error);

      // We're not parsing entire JSON, but rather just looking for sub-strings that are of particular interest
      assertTrue(error.contains("error-code-class\":\"" + errorClass));
      assertTrue(error.contains("error-code\":\"" + errorCode));
    }

    public void assertContains(String subString) throws Exception {
      assertTrue(responseString().contains(subString), "Server response doesn't contain: " + subString);
    }

    private String responseString() {
      if(input.isEmpty()) {
        return error;
      } else {
        return input;
      }
    }
  }

  // Small internal class describing our test case
  public static class TestDescription {
    public String name;       // Name of the test
    public String rest;       // Rest endpoint where we'll send request
    public String method;     // Method that we should use
    public String data;       // Data that we'll be sending as part of POST (NULL for nothing)
    public Validator validator; // Routine to validate the response from the server

    public TestDescription(String name, String rest, String method, String data, Validator validator) {
      this.name = name;
      this.rest = rest;
      this.method = method;
      this.data = data;
      this.validator = validator;
    }
  }

  /**
   * Data preparation for links, jobs and other objects that we might need.
   */
  @BeforeMethod
  public void loadTestData() {
    // Link: first-link
    MLink genericJDBCLink = getClient().createLink("generic-jdbc-connector");
    genericJDBCLink.setName("first-link");
    MConfigList configs = genericJDBCLink.getConnectorLinkConfig();
    configs.getStringInput("linkConfig.jdbcDriver").setValue("org.apache.derby.jdbc.ClientDriver");
    configs.getStringInput("linkConfig.connectionString").setValue("jdbc:derby:memory:invalid-rest-calls-test;create=true");
    configs.getStringInput("linkConfig.username").setValue("sqoop");
    configs.getStringInput("linkConfig.password").setValue("is-awesome");
    getClient().saveLink(genericJDBCLink);
  }

  @AfterMethod
  public void dropTestData() {
    getClient().deleteLink("first-link");
  }

  /**
   * Correct and poisoned requests that we'll be running with expected responses from the server
   */
  public static TestDescription []PROVDER_DATA = new TestDescription[] {
    // End point /version/
    new TestDescription("Valid", "version", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
    }}),
    new TestDescription("Invalid post request", "version", "POST", "Random text", new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0002");
      }}),

    // End point /v1/connector
    new TestDescription("Get all connectors", "v1/connector/all", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
      }}),
    new TestDescription("Get connector by ID", "v1/connector/1", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
      }}),
    new TestDescription("Get connector by name", "v1/connector/generic-jdbc-connector", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
      }}),
    new TestDescription("Get connector by non-existing ID", "v1/connector/666", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0006");
      }}),
    new TestDescription("Get connector by non-existing name", "v1/connector/jarcecs-cool-connector", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0005");
      }}),
    new TestDescription("Invalid post request", "v1/connector", "POST", "Random data", new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0002");
      }}),

    // End point /v1/link

    // Get
    new TestDescription("Get all links", "v1/link/all", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
        assertContains("first-link");
      }}),
    new TestDescription("Get link by name", "v1/link/first-link", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
        assertContains("first-link");
      }}),
    new TestDescription("Get all links for connector", "v1/link/all?cname=generic-jdbc-connector", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(200);
        assertContains("first-link");
      }}),
    new TestDescription("Get non existing link", "v1/link/i-dont-exists", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0006");
        assertContains("Invalid link name: i-dont-exists");
      }}),
    new TestDescription("Get links for non existing connector", "v1/link/all?cname=i-dont-exists", "GET", null, new Validator() {
      @Override
      void validate() throws Exception {
        assertResponseCode(500);
        assertServerException("org.apache.sqoop.server.common.ServerError", "SERVER_0005");
        assertContains("Invalid connector: i-dont-exists");
      }}),

  };

  @DataProvider(name="invalid-rest-calls-test", parallel=false)
  public static Object[][] data() {
    return Iterables.toArray(ParametrizedUtils.toArrayOfArrays(PROVDER_DATA), Object[].class);
  }

  private TestDescription desc;

  @Factory(dataProvider = "invalid-rest-calls-test")
  public InvalidRESTCallsTest(TestDescription desc) {
    this.desc = desc;
  }

  @Override
  public String getTestName() {
    return InvalidRESTCallsTest.class.getName() + " " + desc.rest + "[" + desc.method + "]: " + desc.name;
  }

  @Test
  public void test() throws Exception {
    LOG.info("Start: " + getTestName());

    URL url = new URL(getSqoopServerUrl() +  desc.rest);
    HttpURLConnection connection = new DelegationTokenAuthenticatedURL().openConnection(url, new DelegationTokenAuthenticatedURL.Token());
    connection.setRequestMethod(desc.method);

    if(desc.data != null) {
      connection.setDoOutput(true);

      byte []byteData = desc.data.getBytes(Charset.forName("UTF-8"));
      connection.setRequestProperty("Content-Length", Integer.toString(byteData.length));
      DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
      wr.write(byteData, byteData.length, 0);
      wr.flush();
      wr.close();
    }

    desc.validator.setConnection(connection);
    LOG.info("error = " + desc.validator.error);
    LOG.info("input = " + desc.validator.input);
    desc.validator.validate();

    LOG.info("End: " + getTestName());
  }

}
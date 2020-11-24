package org.apache.hadoop.ozone.s3.signature;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

import org.apache.hadoop.ozone.s3.HeaderPreprocessor;
import org.apache.hadoop.ozone.s3.exception.OS3Exception;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class TestStringToSignProducer {

  @Test
  public void test() throws Exception {

    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.putSingle("Content-Length", "123");
    headers.putSingle("Host", "0.0.0.0:9878");
    headers.putSingle("X-AMZ-Content-Sha256", "Content-SHA");
    headers.putSingle("X-AMZ-Date", "123");
    headers.putSingle("Content-Type", "ozone/mpu");
    headers.putSingle(HeaderPreprocessor.ORIGINAL_CONTENT_TYPE, "streaming");

    String authHeader =
        "AWS4-HMAC-SHA256 Credential=AKIAJWFJK62WUTKNFJJA/20181009/us-east-1"
            + "/s3/aws4_request, "
            + "SignedHeaders=host;x-amz-content-sha256;x-amz-date;"
            + "content-type, "
            + "Signature"
            +
            "=db81b057718d7c1b3b8dffa29933099551c51d787b3b13b9e0f9ebed45982bf2";

    headers.putSingle("Authorization",
        authHeader);

    MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();

    UriInfo uriInfo = Mockito.mock(UriInfo.class);
    Mockito.when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
    Mockito.when(uriInfo.getRequestUri())
        .thenReturn(new URI("http://localhost/buckets"));

    ContainerRequestContext context =
        Mockito.mock(ContainerRequestContext.class);
    Mockito.when(context.getHeaders()).thenReturn(headers);
    Mockito.when(context.getMethod()).thenReturn("GET");
    Mockito.when(context.getUriInfo()).thenReturn(uriInfo);

    final SignatureInfo signatureInfo =
        new AuthorizationV4HeaderParser(authHeader) {
          @Override
          public void validateDateRange(Credential credentialObj)
              throws OS3Exception {
            //NOOP
          }
        }.parseSignature();

    final String signatureBase =
        StringToSignProducer.createSignatureBase(signatureInfo, context);

    Assert.assertEquals(
        "String to sign is invalid",
        "AWS4-HMAC-SHA256\n"
            + "123\n"
            + "20181009/us-east-1/s3/aws4_request\n"
            +
            "f20d4de80af2271545385e8d4c7df608cae70a791c69b97aab1527ed93a0d665",
        signatureBase);
  }

}
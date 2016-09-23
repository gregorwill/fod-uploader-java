package com.fortify.fod.fodapi;

import com.fortify.fod.MessageResponse;
import com.fortify.fod.parser.BsiUrl;
import com.fortify.fod.parser.FortifyCommandLine;
import com.fortify.fod.parser.Proxy;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import okhttp3.Credentials;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.*;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;


public class Api {
    private String baseUrl;
    private OkHttpClient client;
    private boolean useClientId = false;
    private String token;

    private final int segmentLength = 1024 * 1024;        // chunk size

    public Api(String url, Proxy clProxy) {
        baseUrl = url;
        client = Proxy(clProxy);
    }

    public String authenticate(String tenantCode, String username, String password, boolean hasLoginCredentials) {
        String accessToken = "";
        try {
            // Build the form body
            FormBody.Builder formBodyBuilder = new FormBody.Builder().add("scope", "https://hpfod.com/tenant");
            // Has username/password stuff
            if (hasLoginCredentials) {
                formBodyBuilder.add("grant_type", "password")
                        .add("username", tenantCode + "\\" + username)
                        .add("password", password);
            // Has api key/secret
            } else {
                useClientId = true;
                formBodyBuilder.add("grant_type", "client_credentials")
                        .add("client_id", username)
                        .add("client_secret", password);
            }
            RequestBody formBody = formBodyBuilder.build();

            // Create the request
            Request request = new Request.Builder()
                    .url(baseUrl + "/oauth/token")
                    .post(formBody)
                    .build();


            // Get the response
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful())
                throw new IOException("Unexpected code " + response);

            System.out.println("Token created");
            // Read the results and close the response
            String content = IOUtils.toString(response.body().byteStream(), "utf-8");
            response.body().close();

            // Parse the Response
            JsonParser parser = new JsonParser();
            JsonObject obj = parser.parse(content).getAsJsonObject();
            token = obj.get("access_token").getAsString();

            System.out.println(token);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return token;
    }

    public void retireToken() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/oauth/retireToken")
                    .addHeader("Authorization","Bearer " + token)
                    .get()
                    .build();
            Response response = client.newCall(request).execute();

            if (response.isSuccessful()) {
                // Read the results and close the response
                String content = IOUtils.toString(response.body().byteStream(), "utf-8");
                response.body().close();

                Gson gson = new Gson();
                MessageResponse messageResponse = gson.fromJson(content, MessageResponse.class);

                if(messageResponse != null)  // did not get back the expected response
                    System.out.println("Retiring Token : " + messageResponse.getMessage());
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * TODO: entitlementId, entitlementFrequencyType, isRemediationScan, excludeThirdPartyLibs
     * Starts a scan based on the V3 API
     * @param bsiUrl releaseId, assessmentTypeId, technologyStack, languageLevel
     * @param cl scanPreferenceType, ScanPreferenceId, AuditPreferenceId, doSonatypeScan,
     * @return url string
     */
    public void StartStaticScan(BsiUrl bsiUrl, FortifyCommandLine cl) {
        boolean lastFragment = false;
        boolean authenticationSucceeded = true;
        try {
            FileInputStream fs = new FileInputStream(cl.getZipLocation());

            byte[] readByteArray = new byte[segmentLength];
            byte[] sendByteArray;
            int fragmentNumber = 0;
            int byteCount = 0;
            long offset = 0;
            while ((byteCount = fs.read(readByteArray)) != -1) {
                if (byteCount < segmentLength) {
                    fragmentNumber = -1;
                    lastFragment = true;
                    sendByteArray = Arrays.copyOf(readByteArray, byteCount);
                } else {
                    sendByteArray = readByteArray;
                }
                String fragUrl = bsiUrl.getEndpoint() + "/api/v1/release/" + bsiUrl.getProjectVersionId() + "/scan/?"
                        + "&fragNo=" + fragmentNumber + "&offset=" + offset;
                if (bsiUrl.hasAssessmentTypeId())
                    fragUrl += "&assessmentTypeId=" + bsiUrl.getAssessmentTypeId();
                if (bsiUrl.hasTechnologyStack())
                    fragUrl += "&technologyStack=" + bsiUrl.getTechnologyStack();
                if (bsiUrl.hasLanguageLevel())
                    fragUrl += "&languageLevel=" + bsiUrl.getLanguageLevel();
                if (cl.hasScanPreferenceId())
                    fragUrl += "&scanPreferenceId=" + cl.getScanPreferenceId();
                if (cl.hasAuditPreferencesId())
                    fragUrl += "&auditPreferenceId=" + cl.getAuditPreferenceId();
                if (cl.hasRunSonatypeScan())
                    fragUrl += "&doSonatypeScan=" + cl.hasRunSonatypeScan();

                System.out.println(sendByteArray);
                System.out.println(fragUrl);
                MediaType byteArray = MediaType.parse("application/octet-stream");
                Request request = new Request.Builder()
                        .addHeader("Authorization","Bearer " + token)
                        .addHeader("Content-Type", "application/octet-stream")
                        .url(fragUrl)
                        .post(RequestBody.create(byteArray, sendByteArray))
                        .build();
                // Get the response
                Response response = client.newCall(request).execute();

                System.out.println(response);
                System.out.println("success? " + response.isSuccessful());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private OkHttpClient Proxy(Proxy clProxy) {
        if(clProxy != null) {
            OkHttpClient.Builder builder = new OkHttpClient().newBuilder()
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(clProxy.getProxyUri().getHost(), clProxy.getProxyUri().getPort())));

            if (clProxy.hasUsername() && clProxy.hasPassword()) {
                // Include NTDomain and NTWorkstation in auth
                Authenticator proxyAuthenticator;
                if (clProxy.hasNTDomain() && clProxy.hasNTWorkstation()) {
                    proxyAuthenticator = (Route route, Response response) -> {
                        String credentials = new NTCredentials(clProxy.getUsername(), clProxy.getPassword(),
                                clProxy.getNTWorkstation(), clProxy.getNTDomain()).toString();

                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credentials)
                                .build();
                    };
                // Just use username and password
                } else {
                    proxyAuthenticator = (Route route, Response response) -> {
                        String credentials = Credentials.basic(clProxy.getUsername(), clProxy.getPassword());
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credentials)
                                .build();
                    };

                }
                builder.proxyAuthenticator(proxyAuthenticator);
            }
            return builder.build();
        } else {
            return new OkHttpClient();
        }
    }

    public boolean useClientId() {
        return useClientId;
    }
}

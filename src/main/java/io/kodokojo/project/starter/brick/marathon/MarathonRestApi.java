package io.kodokojo.project.starter.brick.marathon;

import com.google.gson.JsonObject;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import retrofit.http.*;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedString;

public interface MarathonRestApi {

    @Headers("Content-Type: application/json" )
    @POST("/v2/apps")
    JsonObject startApplication(@Body TypedString body);

    @DELETE("/v2/apps/{appId}")
    Response killAps(@Path("appId") String appId);

}

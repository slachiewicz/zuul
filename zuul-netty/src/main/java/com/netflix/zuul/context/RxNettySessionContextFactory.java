package com.netflix.zuul.context;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.zuul.rxnetty.RxNettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.subjects.PublishSubject;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

/**
 * User: michaels@netflix.com
 * Date: 2/25/15
 * Time: 4:03 PM
 */
public class RxNettySessionContextFactory implements SessionContextFactory<HttpServerRequest, HttpServerResponse>
{
    private static final Logger LOG = LoggerFactory.getLogger(RxNettySessionContextFactory.class);

    private static final DynamicIntProperty MAX_REQ_BODY_SIZE_PROP = DynamicPropertyFactory.getInstance().getIntProperty(
            "zuul.request.body.max.size", 25 * 1000 * 1024);

    @Override
    public Observable<ZuulMessage> create(SessionContext context, HttpServerRequest httpServerRequest)
    {
        // Get the client IP (ignore XFF headers at this point, as that can be app specific).
        String clientIp = getIpAddress(httpServerRequest.getNettyChannel());

        // TODO - How to get uri scheme from the netty request?
        String scheme = "http";

        // Setup the req/resp message objects.
        HttpRequestMessage request = new HttpRequestMessage(
                context,
                httpServerRequest.getHttpVersion().text(),
                httpServerRequest.getHttpMethod().name().toLowerCase(),
                httpServerRequest.getUri(),
                copyQueryParams(httpServerRequest),
                copyHeaders(httpServerRequest),
                clientIp,
                scheme
        );

        // Buffer the request body, and wrap in an Observable.
        return toObservable(request, httpServerRequest);
    }

    @Override
    public void write(ZuulMessage msg, HttpServerResponse nativeResponse)
    {
        HttpResponseMessage zuulResp = (HttpResponseMessage) msg;

        // Set the response status code.
        nativeResponse.setStatus(HttpResponseStatus.valueOf(zuulResp.getStatus()));

        // Now set all of the response headers - note this is a multi-set in keeping with HTTP semantics
        for (Map.Entry<String, String> entry : zuulResp.getHeaders().entries()) {
            nativeResponse.getHeaders().add(entry.getKey(), entry.getValue());
        }

        // Write response body bytes.
        if (zuulResp.getBody() != null) {
            nativeResponse.writeBytesAndFlush(zuulResp.getBody());
        }
    }

    private Observable<ZuulMessage> toObservable(HttpRequestMessage request, HttpServerRequest<ByteBuf> nettyServerRequest)
    {
        PublishSubject<ByteBuf> cachedContent = PublishSubject.create();
        //UnicastDisposableCachingSubject<ByteBuf> cachedContent = UnicastDisposableCachingSubject.create();

        // Subscribe to the response-content observable (retaining the ByteBufS first).
        nettyServerRequest.getContent().map(ByteBuf::retain).subscribe(cachedContent);

        final int maxReqBodySize = MAX_REQ_BODY_SIZE_PROP.get();

        // Only apply the filters once the request body has been fully read and buffered.
        Observable<ZuulMessage> chain = cachedContent
                .reduce((bb1, bb2) -> {
                    // TODO - this no longer appears to ever be called. Assume always receiving just a single ByteBuf?
                    // Buffer the request body into a single virtual ByteBuf.
                    // and apply some max size to this.
                    if (bb1.readableBytes() > maxReqBodySize) {
                        throw new RuntimeException("Max request body size exceeded! MAX_REQ_BODY_SIZE=" + maxReqBodySize);
                    }
                    return Unpooled.wrappedBuffer(bb1, bb2);

                })
                .map(bodyBuffer -> {
                    // Set the body on Request object.
                    byte[] body = RxNettyUtils.byteBufToBytes(bodyBuffer);
                    request.setBody(body);

                    // Release the ByteBufS
                    if (bodyBuffer.refCnt() > 0) {
                        if (LOG.isDebugEnabled()) LOG.debug("Releasing the server-request ByteBuf.");
                        bodyBuffer.release();
                    }
                    return request;
                });

        return chain;
    }

    private Headers copyHeaders(HttpServerRequest httpServerRequest)
    {
        Headers headers = new Headers();
        for (Map.Entry<String, String> entry : httpServerRequest.getHeaders().entries()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private HttpQueryParams copyQueryParams(HttpServerRequest httpServerRequest)
    {
        HttpQueryParams queryParams = new HttpQueryParams();
        Map<String, List<String>> serverQueryParams = httpServerRequest.getQueryParameters();
        for (String key : serverQueryParams.keySet()) {
            for (String value : serverQueryParams.get(key)) {
                queryParams.add(key, value);
            }
        }
        return queryParams;
    }

    private static String getIpAddress(Channel channel) {
        if (null == channel) {
            return "";
        }

        SocketAddress localSocketAddress = channel.localAddress();
        if (null != localSocketAddress && InetSocketAddress.class.isAssignableFrom(localSocketAddress.getClass())) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) localSocketAddress;
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
        }

        SocketAddress remoteSocketAddr = channel.remoteAddress();
        if (null != remoteSocketAddr && InetSocketAddress.class.isAssignableFrom(remoteSocketAddr.getClass())) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteSocketAddr;
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
        }

        return null;
    }
}
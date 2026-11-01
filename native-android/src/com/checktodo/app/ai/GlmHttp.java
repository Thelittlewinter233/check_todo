package com.checktodo.app.ai;

import android.util.Log;

import com.checktodo.app.domain.AiHttpDiagnostics;
import com.checktodo.app.domain.AiVerdict;
import com.checktodo.app.domain.AiVerifyConfig;
import com.checktodo.app.domain.AiVerdictParser;
import com.checktodo.app.domain.GlmRequest;
import com.checktodo.app.domain.ModelChain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GLM（OpenAI 兼容）调用的 HTTP 薄壳。
 *
 * <p><b>职责边界</b>：只做「连接、发请求、收响应」，判定解析全部交给域层
 * {@link AiVerdictParser}，请求体组装交给 {@link com.checktodo.app.domain.GlmRequest}，
 * 错误文案（endpoint + 响应体摘录）交给 {@link AiHttpDiagnostics}，
 * <b>失败转移决策交给 {@link ModelChain}</b>。</p>
 *
 * <p><b>判定链（N+11）</b>：按 {@code config.effectiveModels()} 顺序最多尝试
 * {@value ModelChain#MAX_MODELS} 个模型。单个模型失败（网络异常 / 非 2xx / 响应不可
 * 解析 / 文本模型不支持图片）→ 转移下一个；401/403（key 无效）不转移，直接终局降级；
 * 全部失败 → 回调 {@code verdict=null} 走既有降级路径。判定成功后 reason 前缀
 * 「[实际模型名] 」，让用户知道是谁判的。</p>
 *
 * <p><b>线程纪律</b>（参照 OnlineExtractionSession 的预算+看门狗做法，此处简化为
 * 单线程执行器 + 连接/读取双超时）：网络绝不在 UI 线程；结果经回调交回调用方，
 * 调用方再 runOnUiThread 上屏。单线程执行器保证不并发打模型（免费额度、也避免
 * 同一张照片被并发判定出两个结论）。</p>
 *
 * <p><b>降级铁律</b>：任何失败（连接异常、超时、非 2xx、额度耗尽、响应不可解析）
 * 都回调 {@code null}——调用方据此走 {@code VerificationPolicy.degraded(...)}
 * 完成打卡 + 待复核，<b>打卡流程永不阻塞</b>。异常绝不外抛。</p>
 *
 * <p><b>诊断日志</b>（TAG = {@value #TAG}）：只记录最终 endpoint、HTTP 状态码与
 * 「第 N 个模型名」——真机 404 排查的教训是「没有 endpoint 就没法看出 base_url 被
 * 重复追加」。<b>绝不记录 api key 与请求体</b>（key 只走 Authorization 头，请求体含
 * 私有照片）。</p>
 */
public final class GlmHttp {

    /** 诊断日志 TAG（adb logcat -s CheckTodoAi）。 */
    public static final String TAG = "CheckTodoAi";
    /** 连接与读取超时（毫秒）：产品决策定为 20s。 */
    public static final int TIMEOUT_MS = 20_000;
    /** 周报长文生成服务端耗时长，文本请求独立使用 90 秒连接/读取超时。 */
    public static final int TEXT_TIMEOUT_MS = 90_000;
    private static final int TEXT_RETRY_DELAY_MS = 2_000;
    /** 单线程执行器：串行化模型调用。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private GlmHttp() {
    }

    /** 判定结果回调（在后台线程调用；调用方负责切回 UI 线程）。 */
    public interface Callback {
        /**
         * @param verdict    解析出的判定（reason 已带「[模型名] 」前缀）；
         *                   null = 整条链失败或不可解析（走降级）
         * @param failureWhy 失败原因（成功时为 null），用于降级记录与用户提示
         */
        void onResult(AiVerdict verdict, String failureWhy);
    }

    /** 「测试连接」结果回调（后台线程调用）。 */
    public interface ProbeCallback {
        /**
         * @param ok        链上至少一个模型连通且 2xx
         * @param modelName 服务端回报的模型名（可能为 null）
         * @param message   成功提示（含模型名）/ 失败原因（含「全部 N 个模型失败」+ 最后原因）
         */
        void onResult(boolean ok, String modelName, String message);
    }

    /**
     * AI 周报的纯文本结果回调（后台线程调用；调用方负责切回 UI 线程）。
     *
     * <p>text = null 表示失败，failureWhy 是面向用户/日志的原因文案。周报失败一律
     * 静默（调用方只 Log.w，不弹错误）——周报是锦上添花的功能，不该打扰用户。</p>
     */
    public interface TextCallback {
        void onResult(String text, String failureWhy);
    }

    /**
     * 异步发起判定请求（判定链：按序最多 3 个模型，失败转移见 {@link ModelChain}）。
     *
     * <p>请求体在执行器线程内按「本次尝试的模型名」逐个组装——链转移时模型字段随之
     * 改变，图片与证据部分完全相同（同一张照片只判定一次结论）。</p>
     *
     * @param config        BYOK 配置（未配 key 时调用方不应进来；这里再兜一层）
     * @param taskName      任务/待办名（提示词里的【任务】）
     * @param evidence      特征描述（提示词里的【证据】，域层做定界符包裹与消毒）
     * @param referenceUri  标准照 data URI
     * @param currentUri    当前照 data URI
     */
    public static void requestAsync(final AiVerifyConfig config, final String taskName,
                                    final String evidence, final String referenceUri,
                                    final String currentUri, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                AiVerdict verdict = null;
                String why = null;
                try {
                    if (config == null || !config.isConfigured()) {
                        why = "未配置 API key";
                    } else {
                        List<String> chain = config.effectiveModels();
                        List<ModelChain.Attempt> attempts = new ArrayList<>();
                        while (true) {
                            ModelChain.Decision decision = ModelChain.decide(chain, attempts);
                            if (decision.finished) {
                                if (decision.degraded) why = decision.failureWhy;
                                break;
                            }
                            String model = decision.nextModel;
                            String body = GlmRequest.buildBodyForModel(config, model, taskName,
                                    evidence, referenceUri, currentUri);
                            AttemptResult result = tryOnce(config, model, body,
                                    attempts.size() + 1, chain.size());
                            attempts.add(result.attempt);
                            if (result.verdict != null) {
                                verdict = ModelChain.prefixReason(result.verdict, model);
                            }
                        }
                    }
                } catch (Exception exception) {
                    // 链内 IOException 已在 tryOnce 里记账为 Attempt（不外抛）；
                    // 这里只兜底意料之外的运行时异常 → 同样走降级。
                    why = "调用异常：" + exception.getClass().getSimpleName();
                }
                if (callback != null) callback.onResult(verdict, why);
            }
        });
    }

    /**
     * 异步「测试连接」：按判定链依次发最小 chat 请求（{@code max_tokens=1}）。
     *
     * <p>首个连通即成功（回报该模型名）；链上全部失败 → 回报「全部 N 个模型失败 +
     * 最后失败原因」；401/403（key 无效）不逐个试完，直接终止。与判定请求共用同一
     * 执行器与 post 路径，因此测试通过 ≈ 判定链路可用。</p>
     */
    public static void requestProbe(final AiVerifyConfig config, final ProbeCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String modelName = null;
                String message;
                try {
                    if (config == null || !config.isConfigured()) {
                        message = "未配置 API key，无法测试";
                    } else {
                        List<String> chain = config.effectiveModels();
                        List<ModelChain.Attempt> attempts = new ArrayList<>();
                        String okModel = null;
                        String failureWhy = null;
                        while (true) {
                            ModelChain.Decision decision = ModelChain.decide(chain, attempts);
                            if (decision.finished) {
                                failureWhy = decision.failureWhy;
                                break;
                            }
                            String model = decision.nextModel;
                            try {
                                HttpResult http = postStatus(config, model,
                                        GlmRequest.buildProbeBody(config, model),
                                        attempts.size() + 1, chain.size());
                                okModel = model;
                                modelName = AiHttpDiagnostics.modelName(http.text);
                                break;
                            } catch (IOException exception) {
                                attempts.add(failedAttempt(model, exception));
                            }
                        }
                        if (okModel != null) {
                            ok = true;
                            message = "连接成功：" + (modelName == null ? okModel : modelName)
                                    + "（endpoint: " + config.endpoint() + "）";
                        } else {
                            message = "全部 " + chain.size() + " 个模型失败："
                                    + (failureWhy == null || failureWhy.isEmpty() ? "未知原因" : failureWhy);
                        }
                    }
                } catch (Exception exception) {
                    // 链内 IOException 已逐个记账（不外抛）；这里只兜运行时异常。
                    message = "测试异常：" + exception.getClass().getSimpleName();
                }
                if (callback != null) callback.onResult(ok, modelName, message);
            }
        });
    }

    /**
     * 异步发起 AI 周报的纯文本请求（无 image_url，{@code max_tokens=}
     * {@value com.checktodo.app.domain.GlmRequest#TEXT_MAX_TOKENS}）。
     *
     * <p>模型固定用 {@link AiVerifyConfig#WEEKLY_MODEL}（周报专用文本模型）——周报是文本任务，
     * 不做链式转移（视觉链位对文本任务没有额外收益，转移只会白耗额度）。</p>
     *
     * <p>与打卡判定共用同一单线程执行器与 post 路径（文本请求用 90s 双超时、同样的
     * Authorization 头、同样的诊断日志口径：只记 endpoint / 状态码 / 模型名，
     * <b>绝不记 api key</b>）。失败 → 回调 text=null + 原因，异常不外抛。</p>
     */
    public static void requestTextAsync(final AiVerifyConfig config, final String prompt,
                                        final TextCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String why = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        text = postTextSync(config, prompt);
                        why = text == null ? "AI 返回内容为空" : null;
                        break;
                    } catch (Exception exception) {
                        boolean retryableNetworkFailure = exception instanceof IOException
                                && !(exception instanceof HttpFailure)
                                && !"未配置 API key".equals(exception.getMessage());
                        if (attempt == 0 && retryableNetworkFailure) {
                            try {
                                Thread.sleep(TEXT_RETRY_DELAY_MS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                why = "调用被中断";
                                break;
                            }
                            continue;
                        }
                        why = exception instanceof IOException
                                ? weeklyNetworkWhy((IOException) exception)
                                : "调用异常：" + exception.getClass().getSimpleName();
                        Log.w(TAG, "text request failed: " + why);
                        break;
                    }
                }
                if (callback != null) callback.onResult(text, why);
            }
        });
    }

    /**
     * 同步版（只在后台线程调用）：发文本请求并返回 {@code choices[0].message.content}。
     *
     * @return 模型正文；内容为空 → null
     * @throws IOException 未配置 key、网络异常、非 2xx
     */
    public static String postTextSync(AiVerifyConfig config, String prompt) throws IOException {
        if (config == null || !config.isConfigured()) {
            Log.w(TAG, "text request skipped: API key not configured");
            throw new IOException("未配置 API key");
        }
        // 周报模型序列：WEEKLY_MODEL 优先，失败（网络/超时/慢到 90s/内容空）逐个落到
        // 判定链模型——视觉模型处理纯文本同样可行（2026-10-31 实测 glm-4.5-flash
        // 免费档排队 >90 秒×2，判定链链首 <20 秒出全文）。
        java.util.List<String> sequence = config.textModelSequence();
        IOException lastFailure = null;
        for (int index = 0; index < sequence.size(); index++) {
            String model = sequence.get(index);
            try {
                HttpResult http = postStatus(config, model,
                        GlmRequest.buildTextBody(config, model, prompt), 0, 0, TEXT_TIMEOUT_MS);
                Log.i(TAG, "text request " + (index + 1) + "/" + sequence.size()
                        + " model=" + model + ", responseChars="
                        + (http.text == null ? 0 : http.text.length()));
                String content = AiVerdictParser.extractContent(http.text);
                if (content != null && !content.trim().isEmpty()) return content.trim();
                Log.w(TAG, "text request empty content, try next model");
            } catch (IOException exception) {
                // 1210（max_tokens 超模型上限）：各模型上限不同——解析服务端回报的上限，
                // 同模型降额重试一次（自适应任意模型），仍失败才转移下一个。
                if (exception instanceof HttpFailure
                        && ((HttpFailure) exception).status == 400) {
                    int clamped = GlmRequest.clampFromError(exception.getMessage(),
                            GlmRequest.TEXT_MAX_TOKENS);
                    if (clamped > 0) {
                        try {
                            HttpResult http = postStatus(config, model, GlmRequest
                                    .buildTextBody(config, model, prompt, clamped),
                                    0, 0, TEXT_TIMEOUT_MS);
                            Log.i(TAG, "text request (clamped max_tokens=" + clamped
                                    + ") model=" + model + ", responseChars="
                                    + (http.text == null ? 0 : http.text.length()));
                            String content = AiVerdictParser.extractContent(http.text);
                            if (content != null && !content.trim().isEmpty()) {
                                return content.trim();
                            }
                        } catch (IOException retryException) {
                            exception = retryException;
                        }
                    }
                }
                lastFailure = exception;
                Log.w(TAG, "text request failed on " + model + ": " + exception.getMessage()
                        + ", try next model");
            }
        }
        if (lastFailure != null) throw lastFailure;
        return null;
    }

    /** 一次模型尝试的结果（判定 + 记账用 Attempt）。 */
    private static final class AttemptResult {
        final AiVerdict verdict;
        final ModelChain.Attempt attempt;

        AttemptResult(AiVerdict verdict, ModelChain.Attempt attempt) {
            this.verdict = verdict;
            this.attempt = attempt;
        }
    }

    /** HTTP 响应（状态码 + 响应体）。 */
    private static final class HttpResult {
        final int status;
        final String text;

        HttpResult(int status, String text) {
            this.status = status;
            this.text = text;
        }
    }

    /** 带 HTTP 状态码的失败（供 {@link ModelChain#classifyFailure} 区分 401/403）。 */
    private static final class HttpFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpFailure(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    /**
     * 单次模型尝试：发请求 → 解析判定。任何失败都转成 {@link ModelChain.Attempt}
     * 记账（不抛到链外），由 {@link ModelChain#decide} 决定是否转移。
     */
    private static AttemptResult tryOnce(AiVerifyConfig config, String model, String body,
                                         int index, int total) {
        try {
            HttpResult http = postStatus(config, model, body, index, total);
            AiVerdict verdict = AiVerdictParser.parseResponse(http.text);
            if (verdict == null) {
                boolean empty = http.text == null || http.text.trim().isEmpty();
                String why = empty ? "模型无响应" : "AI 返回结果无法解析";
                return new AttemptResult(null,
                        ModelChain.Attempt.failure(model, ModelChain.Outcome.RETRYABLE, why, 0));
            }
            return new AttemptResult(verdict, ModelChain.Attempt.success(model));
        } catch (IOException exception) {
            return new AttemptResult(null, failedAttempt(model, exception));
        } catch (Exception exception) {
            return new AttemptResult(null, ModelChain.Attempt.failure(model,
                    ModelChain.Outcome.RETRYABLE,
                    "调用异常：" + exception.getClass().getSimpleName(), 0));
        }
    }

    /** IOException → Attempt（带状态码分类：401/403 终局，其余可重试转移）。 */
    private static ModelChain.Attempt failedAttempt(String model, IOException exception) {
        int status = exception instanceof HttpFailure ? ((HttpFailure) exception).status : 0;
        String why = networkWhy(exception);
        Log.w(TAG, "model=" + model + " failed (HTTP " + status + ")");
        return ModelChain.Attempt.failure(model, ModelChain.classifyFailure(status), why, status);
    }

    /** 失败原因文案（面向用户，避免暴露堆栈）。 */
    private static String networkWhy(IOException exception) {
        String message = exception.getMessage();
        if (exception instanceof java.net.SocketTimeoutException) {
            return "网络超时（20 秒）";
        }
        if (exception instanceof java.net.UnknownHostException) {
            return "无法连接 AI 服务（域名解析失败）";
        }
        return message == null || message.trim().isEmpty()
                ? "网络请求失败" : message.trim();
    }

    private static String weeklyNetworkWhy(IOException exception) {
        if (exception instanceof java.net.SocketTimeoutException) return "网络超时（90 秒）";
        return networkWhy(exception);
    }

    /**
     * 同步 POST（只在执行器线程调用；模型取配置的链首）。
     *
     * @return 响应体文本；非 2xx 抛 IOException（文案由 AiHttpDiagnostics.statusWhy 组装）
     * @throws IOException 连接/读写异常，以及非 2xx（含额度耗尽 4xx）
     */
    public static String post(AiVerifyConfig config, String body) throws IOException {
        return postStatus(config, null, body, 0, 0).text;
    }

    /**
     * 同步 POST 并返回状态码（只在执行器线程调用）。
     *
     * @param model 本次尝试的模型名（仅用于日志；null → 不打链位信息）
     * @param index 链位序号（1 起；0 = 非链式调用，日志省略）
     * @param total 链长（0 = 非链式调用）
     */
    private static HttpResult postStatus(AiVerifyConfig config, String model, String body,
                                         int index, int total) throws IOException {
        return postStatus(config, model, body, index, total, TIMEOUT_MS);
    }

    private static HttpResult postStatus(AiVerifyConfig config, String model, String body,
                                         int index, int total, int timeoutMs) throws IOException {
        HttpURLConnection connection = null;
        String endpoint = config.endpoint();
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            // api key 只进 Authorization 头，绝不进请求体（避免随日志/诊断泄漏）。
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            byte[] payload = body.getBytes(UTF8);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int status = connection.getResponseCode();
            // 诊断：只记 endpoint + 状态码（+ 链位/模型名），无 key、无请求体。
            Log.i(TAG, "POST " + endpoint + " -> HTTP " + status + chainTag(model, index, total));
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = readAll(stream);
            if (status < 200 || status >= 300) {
                Log.w(TAG, "non-2xx, bodyChars=" + (text == null ? 0 : text.length()));
                String why = AiHttpDiagnostics.statusWhy(status, text, endpoint, config.apiKey);
                throw new HttpFailure(why, status);
            }
            return new HttpResult(status, text);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 日志后缀：「（判定链第 N/总数 个模型：xxx）」；非链式调用返回空串。 */
    private static String chainTag(String model, int index, int total) {
        if (index <= 0 || total <= 0 || model == null || model.isEmpty()) return "";
        return "（判定链第 " + index + "/" + total + " 个模型：" + model + "）";
    }

    /**
     * HTTP 状态码 → 用户可读且可诊断的原因（带最终 endpoint 与响应体前 120 字）。
     * 实现在域层 {@link AiHttpDiagnostics}，便于纯 Java 测试。
     */
    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            return new String(buffer.toByteArray(), UTF8);
        } finally {
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败无副作用
        }
    }
}

package com.yubegreen.luonnotar.ui.visual

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/** Shared API 33+ renderer. One instance and one input shader serve every surface in a scene. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LiquidGlassOpticsRenderer {
    private val runtimeShader = RuntimeShader(SHADER_SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = runtimeShader
    }
    private var sceneBitmap: Bitmap? = null
    private var sceneShader: BitmapShader? = null

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        sceneSource: Bitmap,
        sceneOffsetX: Float,
        sceneOffsetY: Float,
        sceneScaleX: Float,
        sceneScaleY: Float,
        dark: Boolean,
        localLuminance: Float,
        touchX: Float,
        touchY: Float,
        touchProgress: Float,
        density: Float
    ) {
        ensureInput(sceneSource)

        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val shortEdge = minOf(width, height)
        val edgeWidth = (shortEdge * 0.16f).coerceIn(14f * density, 34f * density)
        val refraction = (shortEdge * 0.026f).coerceIn(2.2f * density, 7f * density)
        val dispersion = (shortEdge * 0.0035f).coerceIn(0.35f * density, 1.15f * density)
        val blurSpread = (shortEdge * 0.018f).coerceIn(3.2f * density, 6f * density)

        runtimeShader.setFloatUniform("origin", bounds.left, bounds.top)
        runtimeShader.setFloatUniform("sceneOffset", sceneOffsetX, sceneOffsetY)
        runtimeShader.setFloatUniform("sceneScale", sceneScaleX, sceneScaleY)
        runtimeShader.setFloatUniform("size", width, height)
        runtimeShader.setFloatUniform("cornerRadius", radius.coerceAtMost(shortEdge * 0.5f))
        runtimeShader.setFloatUniform("edgeWidth", edgeWidth)
        runtimeShader.setFloatUniform("refraction", refraction)
        runtimeShader.setFloatUniform("dispersion", dispersion)
        runtimeShader.setFloatUniform("blurSpread", blurSpread)
        runtimeShader.setFloatUniform("touchPoint", touchX, touchY)
        runtimeShader.setFloatUniform("touchProgress", touchProgress.coerceIn(0f, 1f))
        runtimeShader.setFloatUniform("touchRadius", maxOf(width, height) * 0.52f)
        runtimeShader.setFloatUniform("touchRefraction", 2.4f * density)
        runtimeShader.setFloatUniform("localLuminance", localLuminance.coerceIn(0f, 1f))

        if (dark) {
            runtimeShader.setFloatUniform("tintColor", 0.025f, 0.035f, 0.052f)
            runtimeShader.setFloatUniform("tintOpacity", 0.15f)
            runtimeShader.setFloatUniform("saturation", 0.96f)
            runtimeShader.setFloatUniform("contrast", 0.92f)
            runtimeShader.setFloatUniform("brightness", 1f)
            runtimeShader.setFloatUniform("darkMode", 1f)
        } else {
            runtimeShader.setFloatUniform("tintColor", 0.97f, 0.985f, 1f)
            runtimeShader.setFloatUniform("tintOpacity", 0.055f)
            runtimeShader.setFloatUniform("saturation", 0.98f)
            runtimeShader.setFloatUniform("contrast", 0.95f)
            runtimeShader.setFloatUniform("brightness", 1f)
            runtimeShader.setFloatUniform("darkMode", 0f)
        }

        canvas.drawRoundRect(bounds, radius, radius, paint)
    }

    fun clear() {
        sceneBitmap = null
        sceneShader = null
    }

    private fun ensureInput(sceneSource: Bitmap) {
        if (sceneBitmap === sceneSource && sceneShader != null) return
        sceneBitmap = sceneSource
        sceneShader = BitmapShader(
            sceneSource,
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP
        ).also {
            it.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            runtimeShader.setInputShader("sceneSource", it)
        }
    }

    private companion object {
        const val SHADER_SOURCE = """
            uniform shader sceneSource;
            uniform float2 origin;
            uniform float2 sceneOffset;
            uniform float2 sceneScale;
            uniform float2 size;
            uniform float cornerRadius;
            uniform float edgeWidth;
            uniform float refraction;
            uniform float dispersion;
            uniform float blurSpread;
            uniform float3 tintColor;
            uniform float tintOpacity;
            uniform float saturation;
            uniform float contrast;
            uniform float brightness;
            uniform float darkMode;
            uniform float localLuminance;
            uniform float2 touchPoint;
            uniform float touchProgress;
            uniform float touchRadius;
            uniform float touchRefraction;

            float roundedRectDistance(float2 point) {
                float2 halfSize = max(size * 0.5 - 1.0, float2(1.0));
                float safeRadius = min(cornerRadius, min(halfSize.x, halfSize.y));
                float2 q = abs(point - size * 0.5) - halfSize + safeRadius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - safeRadius;
            }

            float2 roundedRectNormal(float2 point) {
                float epsilon = 1.1;
                float2 gradient = float2(
                    roundedRectDistance(point + float2(epsilon, 0.0)) -
                        roundedRectDistance(point - float2(epsilon, 0.0)),
                    roundedRectDistance(point + float2(0.0, epsilon)) -
                        roundedRectDistance(point - float2(0.0, epsilon))
                );
                return gradient / max(length(gradient), 0.0001);
            }

            half3 restrainedBlur(float2 point) {
                float2 scenePoint = (point + sceneOffset) * sceneScale;
                float2 spread = float2(blurSpread) * sceneScale;
                half3 color = sceneSource.eval(scenePoint).rgb * 4.0;
                color += sceneSource.eval(scenePoint + float2(-spread.x, 0.0)).rgb * 2.0;
                color += sceneSource.eval(scenePoint + float2(spread.x, 0.0)).rgb * 2.0;
                color += sceneSource.eval(scenePoint + float2(0.0, -spread.y)).rgb * 2.0;
                color += sceneSource.eval(scenePoint + float2(0.0, spread.y)).rgb * 2.0;
                color += sceneSource.eval(scenePoint + float2(-spread.x, -spread.y)).rgb;
                color += sceneSource.eval(scenePoint + float2(spread.x, -spread.y)).rgb;
                color += sceneSource.eval(scenePoint + float2(-spread.x, spread.y)).rgb;
                color += sceneSource.eval(scenePoint + float2(spread.x, spread.y)).rgb;
                return color * 0.0625;
            }

            half4 main(float2 fragCoord) {
                float2 point = fragCoord - origin;
                float distanceToShape = roundedRectDistance(point);
                float rim = smoothstep(-edgeWidth, -0.6, distanceToShape);
                float lens = rim * rim * (3.0 - 2.0 * rim);
                float2 normal = roundedRectNormal(point);

                float2 touchVector = point - touchPoint;
                float touchDistance = length(touchVector);
                float touchEnvelope = exp(-2.9 * touchDistance * touchDistance /
                    max(touchRadius * touchRadius, 1.0)) * touchProgress;
                float2 touchDirection = touchVector / max(touchDistance, 0.001);
                float2 coordinate = fragCoord - normal * refraction * lens +
                    touchDirection * touchEnvelope * touchRefraction;

                half3 soft = restrainedBlur(coordinate);
                float2 chroma = normal * dispersion * lens;
                half3 optical = half3(
                    sceneSource.eval((coordinate + sceneOffset - chroma) * sceneScale).r,
                    sceneSource.eval((coordinate + sceneOffset) * sceneScale).g,
                    sceneSource.eval((coordinate + sceneOffset + chroma) * sceneScale).b
                );
                half3 color = mix(soft, optical, half(0.035 + 0.045 * lens));
                half luma = dot(color, half3(0.2126, 0.7152, 0.0722));
                color = mix(half3(luma), color, half(saturation));
                color = (color - half3(0.5)) * half(contrast) + half3(0.5);
                color *= half(brightness);
                color = mix(color, half3(tintColor), half(tintOpacity));

                float2 lightDirection = normalize(float2(-0.72, -0.69));
                float facingLight = clamp(dot(normal, lightDirection) * 0.5 + 0.5, 0.0, 1.0);
                float localEdgeBoost = mix(0.86, 1.14, abs(localLuminance - 0.5) * 2.0);
                float fresnel = pow(rim, 1.7) * (0.035 + 0.095 * facingLight) * localEdgeBoost;
                float innerShade = pow(rim, 1.9) * (1.0 - facingLight) *
                    mix(0.024, 0.052, darkMode);
                float touchGlow = touchEnvelope * 0.028;
                color += half3(fresnel + touchGlow);
                color *= half(1.0 - innerShade);
                return half4(clamp(color, half3(0.0), half3(1.0)), 1.0);
            }
        """
    }
}

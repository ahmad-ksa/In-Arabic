// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Second reader for subtitle lines ML Kit is unsure about: PaddleOCR's PP-OCRv3 recognizer (ONNX Runtime,
// on the phone). It reads one line image; ML Kit already found where the line is.
// Pre/post-processing checked against Python/onnxruntime with the same model.

package com.inarabic.caption

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

class PaddleReader private constructor(private val env: OrtEnvironment, private val session: OrtSession, private val labels: List<String>)
{
	companion object
	{
		private const val TAG = "InArabicPaddle"
		private const val H = 48

		fun open(context: Context): PaddleReader? = try
		{
			val env = OrtEnvironment.getEnvironment()
			val bytes = context.assets.open("paddle/rec.onnx").use { it.readBytes() }
			val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
			val s = env.createSession(bytes, opts)
			val chars = s.metadata.customMetadata["character"]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
			// CTC labels: blank, the dictionary, then a space
			PaddleReader(env, s, listOf("") + chars + listOf(" "))
		}
		catch(e: Throwable) { Log.w(TAG, "PaddleOCR unavailable", e); null }
	}

	data class Result(val text: String, val confidence: Float)

	fun read(line: Bitmap): Result?
	{
		if(line.width < 8 || line.height < 8) return null
		val w = (line.width * H / line.height.toFloat()).toInt().coerceIn(16, 1280)
		val scaled = Bitmap.createScaledBitmap(line, w, H, true)
		val px = IntArray(w * H)
		scaled.getPixels(px, 0, w, 0, 0, w, H)
		if(scaled !== line) scaled.recycle()
		val plane = w * H
		val data = FloatArray(3 * plane)
		for(i in 0 until plane)
		{
			val c = px[i]
			// channel order B, G, R (the model was trained on OpenCV images)
			data[i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
			data[plane + i] = (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
			data[2 * plane + i] = (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
		}
		return try
		{
			OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, H.toLong(), w.toLong())).use { input ->
				session.run(mapOf(session.inputNames.first() to input)).use { out ->
					@Suppress("UNCHECKED_CAST")
					val probs = (out[0].value as Array<Array<FloatArray>>)[0]
					decode(probs)
				}
			}
		}
		catch(e: Throwable) { Log.w(TAG, "read", e); null }
	}

	/** Greedy CTC: best class per step, drop repeats and blanks. */
	private fun decode(probs: Array<FloatArray>): Result
	{
		val sb = StringBuilder()
		var sum = 0f
		var n = 0
		var last = -1
		for(step in probs)
		{
			var best = 0
			var bp = step[0]
			for(k in 1 until step.size) if(step[k] > bp) { bp = step[k]; best = k }
			if(best != last && best != 0 && best < labels.size)
			{
				sb.append(labels[best]); sum += bp; n++
			}
			last = best
		}
		return Result(sb.toString().trim(), if(n == 0) 0f else sum / n)
	}

	fun close()
	{
		try { session.close() } catch(e: Throwable) { }
	}
}

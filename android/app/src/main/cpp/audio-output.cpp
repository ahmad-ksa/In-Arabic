// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>
#include <cstring>
#include <mutex>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;

	AudioOutput() : stream_callback(this) {}
};

// In Arabic: the last seconds of the game's sound (one channel, 16 kHz), kept even when the phone is muted:
// the dubbing tells a man's voice from a woman's by it (voice/VoicePitch.kt), and «صوت اللعبة» turns its speech
// into the dialogue (caption/SoundDialogue.kt)
#define TAP_LEN 48000
#define TAP_RATE 16000
static std::mutex tap_mutex;
static int16_t tap_buf[TAP_LEN];
static size_t tap_pos = 0, tap_filled = 0;
/** samples kept since the stream began: sample number [tap_total] goes to [tap_pos] */
static uint64_t tap_total = 0;
static uint32_t tap_channels = 2, tap_rate = 48000, tap_decimate = 3;
static int32_t tap_acc = 0;
static uint32_t tap_n = 0;

static void tap_push(const int16_t *buf, size_t samples_count)
{
	uint32_t ch = tap_channels ? tap_channels : 1;
	std::lock_guard<std::mutex> lock(tap_mutex);
	for(size_t i = 0; i + ch <= samples_count; i += ch)
	{
		int32_t v = 0;
		for(uint32_t c = 0; c < ch; c++)
			v += buf[i + c];
		tap_acc += v / (int32_t)ch;
		if(++tap_n < tap_decimate)
			continue;
		tap_buf[tap_pos] = (int16_t)(tap_acc / (int32_t)tap_decimate);
		tap_pos = (tap_pos + 1) % TAP_LEN;
		tap_total++;
		if(tap_filled < TAP_LEN)
			tap_filled++;
		tap_acc = 0;
		tap_n = 0;
	}
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	{
		std::lock_guard<std::mutex> lock(tap_mutex);
		tap_channels = channels;
		tap_decimate = rate >= TAP_RATE ? rate / TAP_RATE : 1;
		tap_rate = rate;
		tap_filled = 0;
	}

	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Exclusive)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(channels)
		->setSampleRate(rate)
		->setCallback(&ao->stream_callback);

	auto result = builder.openManagedStream(ao->stream);
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output opened Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream: %s", oboe::convertToText(result));

	result = ao->stream->start();
	if(result == oboe::Result::OK)
		CHIAKI_LOGI(ao->log, "Audio Output started Oboe stream");
	else
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream: %s", oboe::convertToText(result));
}

// In Arabic: game sound on the phone can be switched off (the TV plays it anyway)
extern "C" { volatile int android_chiaki_audio_muted = 0; }

/**
 * Copies the game's sound from sample number [from] on (-1: the newest [n] samples) into [out]; returns how many.
 * [start]: the number of the first one copied (later than [from] if those are gone), [rate] their sample rate,
 * [total] how many there have been.
 */
extern "C" size_t android_chiaki_audio_from(int64_t from, int16_t *out, size_t n, uint64_t *start, uint32_t *rate, uint64_t *total)
{
	std::lock_guard<std::mutex> lock(tap_mutex);
	*rate = tap_rate / tap_decimate;
	*total = tap_total;
	uint64_t oldest = tap_total - tap_filled;
	uint64_t s = from < 0 ? (tap_total > n ? tap_total - n : 0) : (uint64_t)from;
	if(s < oldest)
		s = oldest;
	if(s > tap_total)
		s = tap_total;
	size_t cnt = (size_t)(tap_total - s);
	if(cnt > n)
		cnt = n;
	size_t ring = (tap_pos + TAP_LEN - (size_t)(tap_total - s)) % TAP_LEN;
	for(size_t i = 0; i < cnt; i++)
		out[i] = tap_buf[(ring + i) % TAP_LEN];
	*start = s;
	return cnt;
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	tap_push(buf, samples_count);
	if(android_chiaki_audio_muted)
		memset(buf, 0, samples_count * sizeof(int16_t));

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
}

package com.example.mediacodecgl;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class AudioDecoder extends Thread implements Runnable {
    private static final String TAG = AudioDecoder.class.getSimpleName();
    private MediaExtractor mMediaExtractor;
    private int mAudioTrackIndex = -1;
    private MediaCodec mAudioDecoder;

    private String mMp4FilePath;

    private int mSessionId;

    public AudioDecoder(String path) {
        mMp4FilePath = path;
    }

    public void setSessionId(int sessionId) {
        this.mSessionId = sessionId;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void run() {
        try {
            mMediaExtractor = new MediaExtractor();
            mMediaExtractor.setDataSource(mMp4FilePath);
            int trackCount = mMediaExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat trackFormat = mMediaExtractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime.contains("audio")) {
                    mAudioTrackIndex = i;
                    /*
                    开始读数据前一定要先选择媒体轨道，否则读取不到数据
                    */
                    mMediaExtractor.selectTrack(mAudioTrackIndex);
                    break;
                }
            }
            if (mAudioTrackIndex == -1) {
                mMediaExtractor.release();
                return;
            }

            MediaFormat format = mMediaExtractor.getTrackFormat(mAudioTrackIndex);
            Log.e(TAG, "音频编码器 run: " + format.toString());
            String audioMime = format.getString(MediaFormat.KEY_MIME);//audio/mp4a-latm
            Log.e(TAG, "音频mimeType： " + audioMime);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);//44100
            Log.e(TAG, "采样率： " + sampleRate);
            long duration = format.getLong(MediaFormat.KEY_DURATION);//45000272
            Log.e(TAG, "音频长度： " + duration);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);//2
            Log.e(TAG, "通道数： " + channelCount);
            String language = format.getString(MediaFormat.KEY_LANGUAGE);//und
            Log.e(TAG, "语言： " + language);
            int aacProfile = 0;
            if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
                aacProfile = format.getInteger(MediaFormat.KEY_AAC_PROFILE);//2
            }
            Log.e(TAG, "AAC配置类型： " + aacProfile);
            if (MediaCodecInfo.CodecProfileLevel.AACObjectLC == aacProfile) {
//                    Log.e(TAG, "run: ");
            }
            getOptionalValue(format);

            mAudioDecoder = MediaCodec.createDecoderByType(audioMime);
            mAudioDecoder.configure(format, null, null, 0);
            mAudioDecoder.start();

            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            if (channelCount >= 2) {
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            }
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    audioFormat
            );
            AudioTrack audioTrack;
            if (mSessionId != 0) {
                Log.e(TAG, "run: 新方式，初始化音频播放器");
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
//                        .setUsage(AudioAttributes.USAGE_MEDIA)
//                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
//                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build();
                AudioFormat.Builder builder = new AudioFormat.Builder();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                    builder.setChannelIndexMask(0);
                }
                AudioFormat audioTrackFormat = builder
//                        .setSampleRate(sampleRate)//部分音频必须设置采样率，部分音频必须乘声道数，否则播放速度不对
//                        .setEncoding(audioFormat)
//                        .setChannelMask(channelConfig)
                        .build();
                audioTrack = new AudioTrack(
                        audioAttributes
                        , audioTrackFormat
                        , minBufferSize
                        , AudioTrack.MODE_STREAM
                        , mSessionId
                );
            } else {
                Log.e(TAG, "run: 旧方式，初始化音频播放器");
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,//这里要乘声道数，否则声道不对
                        channelConfig,
                        audioFormat,
                        minBufferSize,
                        AudioTrack.MODE_STREAM
                );
            }
            audioTrack.play();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            ByteBuffer byteBuffer = ByteBuffer.allocate(minBufferSize);
            int sampleSize = 0;
            while (sampleSize != -1) {
                sampleSize = mMediaExtractor.readSampleData(byteBuffer, 0);
                //填充要解码的数据
                if (sampleSize != -1) {
                    int inputBufferIndex = mAudioDecoder.dequeueInputBuffer(0);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mAudioDecoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.put(byteBuffer);
                            mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, mMediaExtractor.getSampleTime(), 0);
                            mMediaExtractor.advance();
                        }
                    }
                }

                //解码已填充的数据
                int outputBufferIndex = mAudioDecoder.dequeueOutputBuffer(bufferInfo, 0);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mAudioDecoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null) {
                        byte[] bytes = new byte[bufferInfo.size];
                        outputBuffer.position(0);
                        outputBuffer.get(bytes);
                        outputBuffer.clear();
                        Log.d(TAG, "播放音频 run: " + Arrays.toString(bytes));

                        audioTrack.write(bytes, 0, bufferInfo.size);

                        mAudioDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            }
            mMediaExtractor.unselectTrack(mAudioTrackIndex);
            mMediaExtractor.release();
            audioTrack.release();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void getOptionalValue(MediaFormat format) {
        int bitRate = 0;
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);//117640
        }
        Log.e(TAG, "比特率： " + bitRate);
        int profile = 0;
        if (format.containsKey(MediaFormat.KEY_PROFILE)) {
            profile = format.getInteger(MediaFormat.KEY_PROFILE);//2
        }
        Log.e(TAG, "音频配置类型： " + profile);
        int maxBitrate = 0;
        if (format.containsKey("max-bitrate")) {
            maxBitrate = format.getInteger("max-bitrate");//32000
        }
        Log.e(TAG, "最大比特率： " + maxBitrate);
        int trackId = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            trackId = format.getInteger(MediaFormat.KEY_TRACK_ID);//2
        }
        Log.e(TAG, "轨道Id： " + trackId);
        int maxInputSize = 0;
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);//267
        }
        Log.e(TAG, "最大输入量： " + maxInputSize);

        ByteBuffer heapByteBuffer = format.getByteBuffer("csd-0");
        byte[] bytes = heapByteBuffer.array();
        int position = heapByteBuffer.position();
        int limit = heapByteBuffer.limit();
        int capacity = heapByteBuffer.capacity();
        System.out.println();
    }

}
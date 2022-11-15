/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.exoplayer2.transformer;

import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_NO_TRANSFORMATION;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static java.lang.Math.min;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.mp4.SlowMotionData;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.DebugViewProvider;
import com.google.android.exoplayer2.util.Effect;
import com.google.android.exoplayer2.util.FrameProcessor;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/* package */ final class TransformerInternal {

  public interface Listener {

    void onTransformationCompleted();

    void onTransformationError(TransformationException exception);
  }

  private final Context context;
  private final TransformationRequest transformationRequest;
  private final ImmutableList<AudioProcessor> audioProcessors;
  private final ImmutableList<Effect> videoEffects;
  private final Codec.DecoderFactory decoderFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final FrameProcessor.Factory frameProcessorFactory;
  private final DebugViewProvider debugViewProvider;
  private final ExoPlayerAssetLoader exoPlayerAssetLoader;
  private final List<SamplePipeline> samplePipelines;

  private @Transformer.ProgressState int progressState;
  private long durationMs;

  public TransformerInternal(
      Context context,
      TransformationRequest transformationRequest,
      ImmutableList<AudioProcessor> audioProcessors,
      ImmutableList<Effect> videoEffects,
      boolean removeAudio,
      boolean removeVideo,
      MediaSource.Factory mediaSourceFactory,
      Codec.DecoderFactory decoderFactory,
      Codec.EncoderFactory encoderFactory,
      FrameProcessor.Factory frameProcessorFactory,
      Looper looper,
      DebugViewProvider debugViewProvider,
      Clock clock) {
    this.context = context;
    this.transformationRequest = transformationRequest;
    this.audioProcessors = audioProcessors;
    this.videoEffects = videoEffects;
    this.decoderFactory = decoderFactory;
    this.encoderFactory = encoderFactory;
    this.frameProcessorFactory = frameProcessorFactory;
    this.debugViewProvider = debugViewProvider;
    exoPlayerAssetLoader =
        new ExoPlayerAssetLoader(
            context, removeAudio, removeVideo, mediaSourceFactory, looper, clock);
    samplePipelines = new ArrayList<>(/* initialCapacity= */ 2);
    progressState = PROGRESS_STATE_NO_TRANSFORMATION;
  }

  public void start(
      MediaItem mediaItem,
      MuxerWrapper muxerWrapper,
      Listener listener,
      FallbackListener fallbackListener) {
    AssetLoaderListener assetLoaderListener =
        new AssetLoaderListener(mediaItem, muxerWrapper, listener, fallbackListener);
    exoPlayerAssetLoader.start(mediaItem, assetLoaderListener);
    progressState = PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
  }

  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      long positionMs = getCurrentPositionMs();
      progressHolder.progress = min((int) (positionMs * 100 / durationMs), 99);
    }
    return progressState;
  }

  public void release() {
    samplePipelines.clear();
    progressState = PROGRESS_STATE_NO_TRANSFORMATION;
    exoPlayerAssetLoader.release();
  }

  private long getCurrentPositionMs() {
    if (samplePipelines.isEmpty()) {
      return 0;
    }
    long positionMsSum = 0;
    for (int i = 0; i < samplePipelines.size(); i++) {
      positionMsSum += samplePipelines.get(i).getCurrentPositionMs();
    }
    return positionMsSum / samplePipelines.size();
  }

  private class AssetLoaderListener implements ExoPlayerAssetLoader.Listener {

    private final MediaItem mediaItem;
    private final MuxerWrapper muxerWrapper;
    private final TransformerInternal.Listener listener;
    private final FallbackListener fallbackListener;

    private volatile boolean trackRegistered;

    public AssetLoaderListener(
        MediaItem mediaItem,
        MuxerWrapper muxerWrapper,
        Listener listener,
        FallbackListener fallbackListener) {
      this.mediaItem = mediaItem;
      this.muxerWrapper = muxerWrapper;
      this.listener = listener;
      this.fallbackListener = fallbackListener;
    }

    @Override
    public void onDurationMs(long durationMs) {
      // Make progress permanently unavailable if the duration is unknown, so that it doesn't jump
      // to a high value at the end of the transformation if the duration is set once the media is
      // entirely loaded.
      progressState =
          durationMs <= 0 || durationMs == C.TIME_UNSET
              ? PROGRESS_STATE_UNAVAILABLE
              : PROGRESS_STATE_AVAILABLE;
      TransformerInternal.this.durationMs = durationMs;
    }

    @Override
    public void onTrackRegistered() {
      trackRegistered = true;
      muxerWrapper.registerTrack();
      fallbackListener.registerTrack();
    }

    @Override
    public void onAllTracksRegistered() {
      if (!trackRegistered) {
        onError(new IllegalStateException("The output does not contain any tracks."));
      }
    }

    @Override
    public SamplePipeline onTrackAdded(
        Format format, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      SamplePipeline samplePipeline =
          getSamplePipeline(format, streamStartPositionUs, streamOffsetUs);
      samplePipelines.add(samplePipeline);
      return samplePipeline;
    }

    @Override
    public void onError(Exception e) {
      TransformationException transformationException;
      if (e instanceof TransformationException) {
        transformationException = (TransformationException) e;
      } else if (e instanceof PlaybackException) {
        transformationException =
            TransformationException.createForPlaybackException((PlaybackException) e);
      } else {
        transformationException = TransformationException.createForUnexpected(e);
      }
      listener.onTransformationError(transformationException);
    }

    @Override
    public void onEnded() {
      listener.onTransformationCompleted();
    }

    private SamplePipeline getSamplePipeline(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      if (MimeTypes.isAudio(inputFormat.sampleMimeType) && shouldTranscodeAudio(inputFormat)) {
        return new AudioTranscodingSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            audioProcessors,
            decoderFactory,
            encoderFactory,
            muxerWrapper,
            fallbackListener);
      } else if (MimeTypes.isVideo(inputFormat.sampleMimeType)
          && shouldTranscodeVideo(inputFormat, streamStartPositionUs, streamOffsetUs)) {
        return new VideoTranscodingSamplePipeline(
            context,
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            videoEffects,
            frameProcessorFactory,
            decoderFactory,
            encoderFactory,
            muxerWrapper,
            fallbackListener,
            listener::onTransformationError,
            debugViewProvider);
      } else {
        return new PassthroughSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            muxerWrapper,
            fallbackListener);
      }
    }

    private boolean shouldTranscodeAudio(Format inputFormat) {
      if (encoderFactory.audioNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.audioMimeType != null
          && !transformationRequest.audioMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.audioMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.flattenForSlowMotion && isSlowMotion(inputFormat)) {
        return true;
      }
      if (!audioProcessors.isEmpty()) {
        return true;
      }
      return false;
    }

    private boolean isSlowMotion(Format format) {
      @Nullable Metadata metadata = format.metadata;
      if (metadata == null) {
        return false;
      }
      for (int i = 0; i < metadata.length(); i++) {
        if (metadata.get(i) instanceof SlowMotionData) {
          return true;
        }
      }
      return false;
    }

    private boolean shouldTranscodeVideo(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs) {
      if ((streamStartPositionUs - streamOffsetUs) != 0
          && !mediaItem.clippingConfiguration.startsAtKeyFrame) {
        return true;
      }
      if (encoderFactory.videoNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.enableRequestSdrToneMapping) {
        return true;
      }
      if (transformationRequest.forceInterpretHdrVideoAsSdr) {
        return true;
      }
      if (transformationRequest.videoMimeType != null
          && !transformationRequest.videoMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.videoMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (inputFormat.pixelWidthHeightRatio != 1f) {
        return true;
      }
      if (transformationRequest.rotationDegrees != 0f) {
        return true;
      }
      if (transformationRequest.scaleX != 1f) {
        return true;
      }
      if (transformationRequest.scaleY != 1f) {
        return true;
      }
      // The decoder rotates encoded frames for display by inputFormat.rotationDegrees.
      int decodedHeight =
          (inputFormat.rotationDegrees % 180 == 0) ? inputFormat.height : inputFormat.width;
      if (transformationRequest.outputHeight != C.LENGTH_UNSET
          && transformationRequest.outputHeight != decodedHeight) {
        return true;
      }
      if (!videoEffects.isEmpty()) {
        return true;
      }
      return false;
    }
  }
}

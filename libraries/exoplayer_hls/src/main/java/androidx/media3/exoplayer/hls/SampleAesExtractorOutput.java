/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.hls;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Applies HLS SAMPLE-AES identity decryption to media samples before they reach SampleQueue. */
final class SampleAesExtractorOutput implements ExtractorOutput {

  private final ExtractorOutput wrappedOutput;
  private final byte[] key;
  private final byte[] iv;
  private final SparseArray<TrackOutput> trackOutputs;

  public SampleAesExtractorOutput(ExtractorOutput wrappedOutput, byte[] key, byte[] iv) {
    this.wrappedOutput = wrappedOutput;
    this.key = key;
    this.iv = iv;
    this.trackOutputs = new SparseArray<>();
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    @Nullable TrackOutput trackOutput = trackOutputs.get(id);
    if (trackOutput == null) {
      trackOutput =
          new SampleAesTrackOutput(wrappedOutput.track(id, type), type, key, iv);
      trackOutputs.put(id, trackOutput);
    }
    return trackOutput;
  }

  @Override
  public void endTracks() {
    wrappedOutput.endTracks();
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    wrappedOutput.seekMap(seekMap);
  }

  private static final class SampleAesTrackOutput implements TrackOutput {

    private static final int INITIAL_PENDING_DATA_SIZE = 32 * 1024;
    private static final int AES_BLOCK_SIZE = 16;
    private static final int AVC_NAL_TYPE_NON_IDR = 1;
    private static final int AVC_NAL_TYPE_IDR = 5;

    private final TrackOutput wrappedOutput;
    private final @C.TrackType int trackType;
    private final SecretKeySpec keySpec;
    private final IvParameterSpec ivSpec;
    private final ParsableByteArray outputDataWrapper;

    private byte[] pendingData;
    private byte[] outputBuffer;
    private byte[] encryptedBuffer;
    private byte[] decryptedBuffer;
    private byte[] nalBuffer;
    private int pendingLength;
    @Nullable private String sampleMimeType;
    @Nullable private Cipher cipher;

    public SampleAesTrackOutput(
        TrackOutput wrappedOutput, @C.TrackType int trackType, byte[] key, byte[] iv) {
      this.wrappedOutput = wrappedOutput;
      this.trackType = trackType;
      this.keySpec = new SecretKeySpec(key, "AES");
      this.ivSpec = new IvParameterSpec(iv);
      this.outputDataWrapper = new ParsableByteArray(new byte[0]);
      this.pendingData = new byte[INITIAL_PENDING_DATA_SIZE];
      this.outputBuffer = new byte[INITIAL_PENDING_DATA_SIZE];
      this.encryptedBuffer = new byte[INITIAL_PENDING_DATA_SIZE];
      this.decryptedBuffer = new byte[INITIAL_PENDING_DATA_SIZE];
      this.nalBuffer = new byte[INITIAL_PENDING_DATA_SIZE];
    }

    @Override
    public void durationUs(long durationUs) {
      wrappedOutput.durationUs(durationUs);
    }

    @Override
    public void format(Format format) {
      sampleMimeType = format.sampleMimeType;
      wrappedOutput.format(format);
    }

    @Override
    public int sampleData(
        DataReader input,
        int length,
        boolean allowEndOfInput,
        @SampleDataPart int sampleDataPart)
        throws IOException {
      ensurePendingCapacity(length);
      int bytesRead = input.read(pendingData, pendingLength, length);
      if (bytesRead == C.RESULT_END_OF_INPUT) {
        if (allowEndOfInput) {
          return C.RESULT_END_OF_INPUT;
        }
        throw new EOFException();
      }
      pendingLength += bytesRead;
      return bytesRead;
    }

    @Override
    public void sampleData(
        ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
      ensurePendingCapacity(length);
      data.readBytes(pendingData, pendingLength, length);
      pendingLength += length;
    }

    @Override
    public void sampleMetadata(
        long timeUs,
        @C.BufferFlags int flags,
        int size,
        int offset,
        @Nullable CryptoData cryptoData) {
      int bytesReferencedByMetadata = size + offset;
      if (bytesReferencedByMetadata > pendingLength) {
        flushPendingWithoutDecrypting();
        wrappedOutput.sampleMetadata(timeUs, flags, size, offset, cryptoData);
        return;
      }

      int sampleStart = pendingLength - bytesReferencedByMetadata;
      if (sampleStart > 0) {
        discardPendingBytes(sampleStart);
      }

      int sampleLength = size;
      if (shouldDecryptAvc()) {
        sampleLength = decryptAvcSample(pendingData, sampleLength);
      } else if (shouldDecryptAac()) {
        decryptAacSample(pendingData, sampleLength);
      }

      outputDataWrapper.reset(pendingData, sampleLength);
      wrappedOutput.sampleData(outputDataWrapper, sampleLength);
      wrappedOutput.sampleMetadata(timeUs, flags, sampleLength, /* offset= */ 0, cryptoData);

      if (offset > 0) {
        System.arraycopy(pendingData, size, pendingData, 0, offset);
      }
      pendingLength = offset;
    }

    private boolean shouldDecryptAvc() {
      return trackType == C.TRACK_TYPE_VIDEO && MimeTypes.VIDEO_H264.equals(sampleMimeType);
    }

    private boolean shouldDecryptAac() {
      return trackType == C.TRACK_TYPE_AUDIO && MimeTypes.AUDIO_AAC.equals(sampleMimeType);
    }

    private void ensurePendingCapacity(int additionalLength) {
      int requiredLength = pendingLength + additionalLength;
      if (requiredLength <= pendingData.length) {
        return;
      }
      pendingData = Arrays.copyOf(pendingData, Math.max(requiredLength, pendingData.length * 2));
    }

    private void discardPendingBytes(int length) {
      int remainingLength = pendingLength - length;
      if (remainingLength > 0) {
        System.arraycopy(pendingData, length, pendingData, 0, remainingLength);
      }
      pendingLength = remainingLength;
    }

    private void flushPendingWithoutDecrypting() {
      if (pendingLength == 0) {
        return;
      }
      outputDataWrapper.reset(pendingData, pendingLength);
      wrappedOutput.sampleData(outputDataWrapper, pendingLength);
      pendingLength = 0;
    }

    private static byte[] ensureScratchCapacity(byte[] buffer, int requiredLength) {
      return requiredLength <= buffer.length
          ? buffer
          : new byte[Math.max(requiredLength, buffer.length * 2)];
    }

    private void decryptAacSample(byte[] sample, int sampleLength) {
      if (sampleLength <= AES_BLOCK_SIZE) {
        return;
      }
      int encryptedLength = ((sampleLength - AES_BLOCK_SIZE) / AES_BLOCK_SIZE) * AES_BLOCK_SIZE;
      if (encryptedLength == 0) {
        return;
      }
      outputBuffer = ensureScratchCapacity(outputBuffer, encryptedLength);
      int decryptedLength =
          decryptAesCbc(sample, AES_BLOCK_SIZE, encryptedLength, outputBuffer, /* outputOffset= */ 0);
      System.arraycopy(outputBuffer, 0, sample, AES_BLOCK_SIZE, decryptedLength);
    }

    private int decryptAvcSample(byte[] sample, int sampleLength) {
      int nalStartCodeOffset = findStartCode(sample, /* offset= */ 0, sampleLength);
      if (nalStartCodeOffset == C.INDEX_UNSET) {
        return sampleLength;
      }

      outputBuffer = ensureScratchCapacity(outputBuffer, sampleLength);
      int inputOffset = 0;
      int outputOffset = 0;
      while (nalStartCodeOffset != C.INDEX_UNSET) {
        if (nalStartCodeOffset > inputOffset) {
          int length = nalStartCodeOffset - inputOffset;
          System.arraycopy(sample, inputOffset, outputBuffer, outputOffset, length);
          outputOffset += length;
        }

        int startCodeLength = startCodeLength(sample, nalStartCodeOffset);
        System.arraycopy(sample, nalStartCodeOffset, outputBuffer, outputOffset, startCodeLength);
        outputOffset += startCodeLength;

        int nalStartOffset = nalStartCodeOffset + startCodeLength;
        int nextNalStartCodeOffset = findStartCode(sample, nalStartOffset, sampleLength);
        int nalEndOffset =
            nextNalStartCodeOffset == C.INDEX_UNSET ? sampleLength : nextNalStartCodeOffset;
        if (nalStartOffset < nalEndOffset) {
          int nalUnitType = sample[nalStartOffset] & 0x1F;
          int nalLength = nalEndOffset - nalStartOffset;
          if ((nalUnitType == AVC_NAL_TYPE_NON_IDR || nalUnitType == AVC_NAL_TYPE_IDR)
              && nalLength > 48) {
            nalBuffer = ensureScratchCapacity(nalBuffer, nalLength);
            int unescapedNalLength =
                unescapeNal(
                    sample,
                    nalStartOffset,
                    nalEndOffset,
                    nalBuffer,
                    /* outputStartOffset= */ 0);
            decryptAvcNal(nalBuffer, /* nalOffset= */ 0, unescapedNalLength);
            outputBuffer =
                ensureScratchCapacity(
                    outputBuffer, outputOffset + getEscapedNalMaxLength(unescapedNalLength));
            outputOffset =
                escapeNal(
                    nalBuffer,
                    /* inputOffset= */ 0,
                    unescapedNalLength,
                    outputBuffer,
                    outputOffset);
          } else {
            System.arraycopy(sample, nalStartOffset, outputBuffer, outputOffset, nalLength);
            outputOffset += nalLength;
          }
        }
        inputOffset = nalEndOffset;
        nalStartCodeOffset = nextNalStartCodeOffset;
      }

      if (inputOffset < sampleLength) {
        int length = sampleLength - inputOffset;
        System.arraycopy(sample, inputOffset, outputBuffer, outputOffset, length);
        outputOffset += length;
      }
      System.arraycopy(outputBuffer, 0, sample, 0, outputOffset);
      return outputOffset;
    }

    private void decryptAvcNal(byte[] nalData, int nalOffset, int nalLength) {
      int encryptedLength = getAvcEncryptedLength(nalLength);
      if (encryptedLength == 0) {
        return;
      }

      encryptedBuffer = ensureScratchCapacity(encryptedBuffer, encryptedLength);
      decryptedBuffer = ensureScratchCapacity(decryptedBuffer, encryptedLength);
      int encryptedDataOffset = 0;
      int nalEndOffset = nalOffset + nalLength;
      for (int offset = nalOffset + 32; offset < nalEndOffset - AES_BLOCK_SIZE; offset += 160) {
        System.arraycopy(nalData, offset, encryptedBuffer, encryptedDataOffset, AES_BLOCK_SIZE);
        encryptedDataOffset += AES_BLOCK_SIZE;
      }

      int decryptedLength = decryptAesCbc(encryptedBuffer, 0, encryptedLength, decryptedBuffer, 0);
      int decryptedDataOffset = 0;
      for (int offset = nalOffset + 32; offset < nalEndOffset - AES_BLOCK_SIZE; offset += 160) {
        if (decryptedDataOffset + AES_BLOCK_SIZE > decryptedLength) {
          return;
        }
        System.arraycopy(decryptedBuffer, decryptedDataOffset, nalData, offset, AES_BLOCK_SIZE);
        decryptedDataOffset += AES_BLOCK_SIZE;
      }
    }

    private static int getAvcEncryptedLength(int nalLength) {
      int encryptedLength = 0;
      for (int offset = 32; offset < nalLength - AES_BLOCK_SIZE; offset += 160) {
        encryptedLength += AES_BLOCK_SIZE;
      }
      return encryptedLength;
    }

    private static int unescapeNal(
        byte[] sample,
        int nalStartOffset,
        int nalEndOffset,
        byte[] output,
        int outputStartOffset) {
      int inputOffset = nalStartOffset;
      int outputOffset = outputStartOffset;
      while (inputOffset < nalEndOffset) {
        if (nalEndOffset - inputOffset > 3
            && sample[inputOffset] == 0
            && sample[inputOffset + 1] == 0
            && sample[inputOffset + 2] == 3) {
          output[outputOffset++] = sample[inputOffset++];
          output[outputOffset++] = sample[inputOffset++];
          inputOffset++;
        } else {
          output[outputOffset++] = sample[inputOffset++];
        }
      }
      return outputOffset - outputStartOffset;
    }

    private static int getEscapedNalMaxLength(int nalLength) {
      return nalLength + (nalLength / 2) + 1;
    }

    private static int escapeNal(
        byte[] sample,
        int inputOffset,
        int inputLength,
        byte[] output,
        int outputOffset) {
      int inputEndOffset = inputOffset + inputLength;
      int consecutiveZeros = 0;
      while (inputOffset < inputEndOffset) {
        byte value = sample[inputOffset++];
        if (consecutiveZeros >= 2 && (value & 0xFF) <= 0x03) {
          output[outputOffset++] = 0x03;
          consecutiveZeros = 0;
        }
        output[outputOffset++] = value;
        consecutiveZeros = value == 0 ? consecutiveZeros + 1 : 0;
      }
      return outputOffset;
    }

    private int decryptAesCbc(
        byte[] encryptedData,
        int encryptedOffset,
        int encryptedLength,
        byte[] decryptedData,
        int decryptedOffset) {
      try {
        initCipher();
        return cipher.doFinal(encryptedData, encryptedOffset, encryptedLength, decryptedData, decryptedOffset);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Failed to decrypt HLS SAMPLE-AES sample", e);
      }
    }

    private void initCipher() throws GeneralSecurityException {
      if (cipher == null) {
        cipher = Cipher.getInstance("AES/CBC/NoPadding");
      }
      cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
    }

    private static int findStartCode(byte[] data, int offset, int limit) {
      for (int i = offset; i <= limit - 3; i++) {
        if (data[i] == 0 && data[i + 1] == 0) {
          if (data[i + 2] == 1) {
            return i;
          }
          if (i <= limit - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
            return i;
          }
        }
      }
      return C.INDEX_UNSET;
    }

    private static int startCodeLength(byte[] data, int startCodeOffset) {
      return data[startCodeOffset + 2] == 1 ? 3 : 4;
    }
  }
}

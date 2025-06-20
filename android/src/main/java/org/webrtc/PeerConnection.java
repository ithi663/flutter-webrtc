package org.webrtc;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class PeerConnection {
   private final List<MediaStream> localStreams;
   private final long nativePeerConnection;
   private List<RtpSender> senders;
   private List<RtpReceiver> receivers;
   private List<RtpTransceiver> transceivers;

   public PeerConnection(NativePeerConnectionFactory factory) {
      this(factory.createNativePeerConnection());
   }

   PeerConnection(long nativePeerConnection) {
      this.localStreams = new ArrayList();
      this.senders = new ArrayList();
      this.receivers = new ArrayList();
      this.transceivers = new ArrayList();
      this.nativePeerConnection = nativePeerConnection;
   }

   public SessionDescription getLocalDescription() {
      return this.nativeGetLocalDescription();
   }

   public SessionDescription getRemoteDescription() {
      return this.nativeGetRemoteDescription();
   }

   public RtcCertificatePem getCertificate() {
      return this.nativeGetCertificate();
   }

   public DataChannel createDataChannel(String label, DataChannel.Init init) {
      return this.nativeCreateDataChannel(label, init);
   }

   public void createOffer(SdpObserver observer, MediaConstraints constraints) {
      this.nativeCreateOffer(observer, constraints);
   }

   public void createAnswer(SdpObserver observer, MediaConstraints constraints) {
      this.nativeCreateAnswer(observer, constraints);
   }

   public void setLocalDescription(SdpObserver observer) {
      this.nativeSetLocalDescriptionAutomatically(observer);
   }

   public void setLocalDescription(SdpObserver observer, SessionDescription sdp) {
      this.nativeSetLocalDescription(observer, sdp);
   }

   public void setRemoteDescription(SdpObserver observer, SessionDescription sdp) {
      this.nativeSetRemoteDescription(observer, sdp);
   }

   public void restartIce() {
      this.nativeRestartIce();
   }

   public void setAudioPlayout(boolean playout) {
      this.nativeSetAudioPlayout(playout);
   }

   public void setAudioRecording(boolean recording) {
      this.nativeSetAudioRecording(recording);
   }

   public boolean setConfiguration(PeerConnection.RTCConfiguration config) {
      return this.nativeSetConfiguration(config);
   }

   public boolean addIceCandidate(IceCandidate candidate) {
      return this.nativeAddIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
   }

   public void addIceCandidate(IceCandidate candidate, AddIceObserver observer) {
      this.nativeAddIceCandidateWithObserver(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp, observer);
   }

   public boolean removeIceCandidates(IceCandidate[] candidates) {
      return this.nativeRemoveIceCandidates(candidates);
   }

   public boolean addStream(MediaStream stream) {
      boolean ret = this.nativeAddLocalStream(stream.getNativeMediaStream());
      if (!ret) {
         return false;
      } else {
         this.localStreams.add(stream);
         return true;
      }
   }

   public void removeStream(MediaStream stream) {
      this.nativeRemoveLocalStream(stream.getNativeMediaStream());
      this.localStreams.remove(stream);
   }

   public RtpSender createSender(String kind, String stream_id) {
      RtpSender newSender = this.nativeCreateSender(kind, stream_id);
      if (newSender != null) {
         this.senders.add(newSender);
      }

      return newSender;
   }

   public List<RtpSender> getSenders() {
      Iterator var1 = this.senders.iterator();

      while(var1.hasNext()) {
         RtpSender sender = (RtpSender)var1.next();
         sender.dispose();
      }

      this.senders = this.nativeGetSenders();
      return Collections.unmodifiableList(this.senders);
   }

   public List<RtpReceiver> getReceivers() {
      Iterator var1 = this.receivers.iterator();

      while(var1.hasNext()) {
         RtpReceiver receiver = (RtpReceiver)var1.next();
         receiver.dispose();
      }

      this.receivers = this.nativeGetReceivers();
      return Collections.unmodifiableList(this.receivers);
   }

   public List<RtpTransceiver> getTransceivers() {
      Iterator var1 = this.transceivers.iterator();

      while(var1.hasNext()) {
         RtpTransceiver transceiver = (RtpTransceiver)var1.next();
         transceiver.dispose();
      }

      this.transceivers = this.nativeGetTransceivers();
      return Collections.unmodifiableList(this.transceivers);
   }

   public RtpSender addTrack(MediaStreamTrack track) {
      return this.addTrack(track, Collections.emptyList());
   }

   public RtpSender addTrack(MediaStreamTrack track, List<String> streamIds) {
      if (track != null && streamIds != null) {
         RtpSender newSender = this.nativeAddTrack(track.getNativeMediaStreamTrack(), streamIds);
         if (newSender == null) {
            throw new IllegalStateException("C++ addTrack failed.");
         } else {
            this.senders.add(newSender);
            return newSender;
         }
      } else {
         throw new NullPointerException("No MediaStreamTrack specified in addTrack.");
      }
   }

   public boolean removeTrack(RtpSender sender) {
      if (sender == null) {
         throw new NullPointerException("No RtpSender specified for removeTrack.");
      } else {
         return this.nativeRemoveTrack(sender.getNativeRtpSender());
      }
   }

   public RtpTransceiver addTransceiver(MediaStreamTrack track) {
      return this.addTransceiver(track, new RtpTransceiver.RtpTransceiverInit());
   }

   public RtpTransceiver addTransceiver(MediaStreamTrack track, @Nullable RtpTransceiver.RtpTransceiverInit init) {
      if (track == null) {
         throw new NullPointerException("No MediaStreamTrack specified for addTransceiver.");
      } else {
         if (init == null) {
            init = new RtpTransceiver.RtpTransceiverInit();
         }

         RtpTransceiver newTransceiver = this.nativeAddTransceiverWithTrack(track.getNativeMediaStreamTrack(), init);
         if (newTransceiver == null) {
            throw new IllegalStateException("C++ addTransceiver failed.");
         } else {
            this.transceivers.add(newTransceiver);
            return newTransceiver;
         }
      }
   }

   public RtpTransceiver addTransceiver(MediaStreamTrack.MediaType mediaType) {
      return this.addTransceiver(mediaType, new RtpTransceiver.RtpTransceiverInit());
   }

   public RtpTransceiver addTransceiver(MediaStreamTrack.MediaType mediaType, @Nullable RtpTransceiver.RtpTransceiverInit init) {
      if (mediaType == null) {
         throw new NullPointerException("No MediaType specified for addTransceiver.");
      } else {
         if (init == null) {
            init = new RtpTransceiver.RtpTransceiverInit();
         }

         RtpTransceiver newTransceiver = this.nativeAddTransceiverOfType(mediaType, init);
         if (newTransceiver == null) {
            throw new IllegalStateException("C++ addTransceiver failed.");
         } else {
            this.transceivers.add(newTransceiver);
            return newTransceiver;
         }
      }
   }

   /** @deprecated */
   @Deprecated
   public boolean getStats(StatsObserver observer, @Nullable MediaStreamTrack track) {
      return this.nativeOldGetStats(observer, track == null ? 0L : track.getNativeMediaStreamTrack());
   }

   public void getStats(RTCStatsCollectorCallback callback) {
      this.nativeNewGetStats(callback);
   }

   public void getStats(RtpSender sender, RTCStatsCollectorCallback callback) {
      this.nativeNewGetStatsSender(sender.getNativeRtpSender(), callback);
   }

   public void getStats(RtpReceiver receiver, RTCStatsCollectorCallback callback) {
      this.nativeNewGetStatsReceiver(receiver.getNativeRtpReceiver(), callback);
   }

   public boolean setBitrate(Integer min, Integer current, Integer max) {
      return this.nativeSetBitrate(min, current, max);
   }

   public boolean startRtcEventLog(int file_descriptor, int max_size_bytes) {
      return this.nativeStartRtcEventLog(file_descriptor, max_size_bytes);
   }

   public void stopRtcEventLog() {
      this.nativeStopRtcEventLog();
   }

   public PeerConnection.SignalingState signalingState() {
      return this.nativeSignalingState();
   }

   public PeerConnection.IceConnectionState iceConnectionState() {
      return this.nativeIceConnectionState();
   }

   public PeerConnection.PeerConnectionState connectionState() {
      return this.nativeConnectionState();
   }

   public PeerConnection.IceGatheringState iceGatheringState() {
      return this.nativeIceGatheringState();
   }

   public void close() {
      this.nativeClose();
   }

   public void dispose() {
      this.close();
      Iterator var1 = this.localStreams.iterator();

      while(var1.hasNext()) {
         MediaStream stream = (MediaStream)var1.next();
         this.nativeRemoveLocalStream(stream.getNativeMediaStream());
         stream.dispose();
      }

      this.localStreams.clear();
      var1 = this.senders.iterator();

      while(var1.hasNext()) {
         RtpSender sender = (RtpSender)var1.next();
         sender.dispose();
      }

      this.senders.clear();
      var1 = this.receivers.iterator();

      while(var1.hasNext()) {
         RtpReceiver receiver = (RtpReceiver)var1.next();
         receiver.dispose();
      }

      var1 = this.transceivers.iterator();

      while(var1.hasNext()) {
         RtpTransceiver transceiver = (RtpTransceiver)var1.next();
         transceiver.dispose();
      }

      this.transceivers.clear();
      this.receivers.clear();
      nativeFreeOwnedPeerConnection(this.nativePeerConnection);
   }

   public long getNativePeerConnection() {
      return this.nativeGetNativePeerConnection();
   }

   @CalledByNative
   long getNativeOwnedPeerConnection() {
      return this.nativePeerConnection;
   }

   public static long createNativePeerConnectionObserver(PeerConnection.Observer observer) {
      return nativeCreatePeerConnectionObserver(observer);
   }

   private native long nativeGetNativePeerConnection();

   private native SessionDescription nativeGetLocalDescription();

   private native SessionDescription nativeGetRemoteDescription();

   private native RtcCertificatePem nativeGetCertificate();

   private native DataChannel nativeCreateDataChannel(String var1, DataChannel.Init var2);

   private native void nativeCreateOffer(SdpObserver var1, MediaConstraints var2);

   private native void nativeCreateAnswer(SdpObserver var1, MediaConstraints var2);

   private native void nativeSetLocalDescriptionAutomatically(SdpObserver var1);

   private native void nativeSetLocalDescription(SdpObserver var1, SessionDescription var2);

   private native void nativeSetRemoteDescription(SdpObserver var1, SessionDescription var2);

   private native void nativeRestartIce();

   private native void nativeSetAudioPlayout(boolean var1);

   private native void nativeSetAudioRecording(boolean var1);

   private native boolean nativeSetBitrate(Integer var1, Integer var2, Integer var3);

   private native PeerConnection.SignalingState nativeSignalingState();

   private native PeerConnection.IceConnectionState nativeIceConnectionState();

   private native PeerConnection.PeerConnectionState nativeConnectionState();

   private native PeerConnection.IceGatheringState nativeIceGatheringState();

   private native void nativeClose();

   private static native long nativeCreatePeerConnectionObserver(PeerConnection.Observer var0);

   private static native void nativeFreeOwnedPeerConnection(long var0);

   private native boolean nativeSetConfiguration(PeerConnection.RTCConfiguration var1);

   private native boolean nativeAddIceCandidate(String var1, int var2, String var3);

   private native void nativeAddIceCandidateWithObserver(String var1, int var2, String var3, AddIceObserver var4);

   private native boolean nativeRemoveIceCandidates(IceCandidate[] var1);

   private native boolean nativeAddLocalStream(long var1);

   private native void nativeRemoveLocalStream(long var1);

   private native boolean nativeOldGetStats(StatsObserver var1, long var2);

   private native void nativeNewGetStats(RTCStatsCollectorCallback var1);

   private native void nativeNewGetStatsSender(long var1, RTCStatsCollectorCallback var3);

   private native void nativeNewGetStatsReceiver(long var1, RTCStatsCollectorCallback var3);

   private native RtpSender nativeCreateSender(String var1, String var2);

   private native List<RtpSender> nativeGetSenders();

   private native List<RtpReceiver> nativeGetReceivers();

   private native List<RtpTransceiver> nativeGetTransceivers();

   private native RtpSender nativeAddTrack(long var1, List<String> var3);

   private native boolean nativeRemoveTrack(long var1);

   private native RtpTransceiver nativeAddTransceiverWithTrack(long var1, RtpTransceiver.RtpTransceiverInit var3);

   private native RtpTransceiver nativeAddTransceiverOfType(MediaStreamTrack.MediaType var1, RtpTransceiver.RtpTransceiverInit var2);

   private native boolean nativeStartRtcEventLog(int var1, int var2);

   private native void nativeStopRtcEventLog();

   public static class RTCConfiguration {
      public PeerConnection.IceTransportsType iceTransportsType;
      public List<PeerConnection.IceServer> iceServers;
      public PeerConnection.BundlePolicy bundlePolicy;
      @Nullable
      public RtcCertificatePem certificate;
      public PeerConnection.RtcpMuxPolicy rtcpMuxPolicy;
      public PeerConnection.TcpCandidatePolicy tcpCandidatePolicy;
      public PeerConnection.CandidateNetworkPolicy candidateNetworkPolicy;
      public int audioJitterBufferMaxPackets;
      public boolean audioJitterBufferFastAccelerate;
      public int iceConnectionReceivingTimeout;
      public int iceBackupCandidatePairPingInterval;
      public PeerConnection.KeyType keyType;
      public PeerConnection.ContinualGatheringPolicy continualGatheringPolicy;
      public int iceCandidatePoolSize;
      /** @deprecated */
      @Deprecated
      public boolean pruneTurnPorts;
      public PeerConnection.PortPrunePolicy turnPortPrunePolicy;
      public boolean presumeWritableWhenFullyRelayed;
      public boolean surfaceIceCandidatesOnIceTransportTypeChanged;
      @Nullable
      public Integer iceCheckIntervalStrongConnectivityMs;
      @Nullable
      public Integer iceCheckIntervalWeakConnectivityMs;
      @Nullable
      public Integer iceCheckMinInterval;
      @Nullable
      public Integer iceUnwritableTimeMs;
      @Nullable
      public Integer iceUnwritableMinChecks;
      @Nullable
      public Integer stunCandidateKeepaliveIntervalMs;
      @Nullable
      public Integer stableWritableConnectionPingIntervalMs;
      public boolean disableIPv6OnWifi;
      public int maxIPv6Networks;
      public boolean enableDscp;
      public boolean enableCpuOveruseDetection;
      public boolean suspendBelowMinBitrate;
      @Nullable
      public Integer screencastMinBitrate;
      public PeerConnection.AdapterType networkPreference;
      public PeerConnection.SdpSemantics sdpSemantics;
      @Nullable
      public TurnCustomizer turnCustomizer;
      public boolean activeResetSrtpParams;
      @Nullable
      public CryptoOptions cryptoOptions;
      @Nullable
      public String turnLoggingId;
      public boolean enableImplicitRollback;
      public boolean offerExtmapAllowMixed;
      public boolean enableIceGatheringOnAnyAddressPorts;

      public RTCConfiguration(List<PeerConnection.IceServer> iceServers) {
         this.iceTransportsType = PeerConnection.IceTransportsType.ALL;
         this.bundlePolicy = PeerConnection.BundlePolicy.BALANCED;
         this.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
         this.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
         this.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
         this.iceServers = iceServers;
         this.audioJitterBufferMaxPackets = 50;
         this.audioJitterBufferFastAccelerate = false;
         this.iceConnectionReceivingTimeout = -1;
         this.iceBackupCandidatePairPingInterval = -1;
         this.keyType = PeerConnection.KeyType.ECDSA;
         this.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
         this.iceCandidatePoolSize = 0;
         this.pruneTurnPorts = false;
         this.turnPortPrunePolicy = PeerConnection.PortPrunePolicy.NO_PRUNE;
         this.presumeWritableWhenFullyRelayed = false;
         this.surfaceIceCandidatesOnIceTransportTypeChanged = false;
         this.iceCheckIntervalStrongConnectivityMs = null;
         this.iceCheckIntervalWeakConnectivityMs = null;
         this.iceCheckMinInterval = null;
         this.iceUnwritableTimeMs = null;
         this.iceUnwritableMinChecks = null;
         this.stunCandidateKeepaliveIntervalMs = null;
         this.stableWritableConnectionPingIntervalMs = null;
         this.disableIPv6OnWifi = false;
         this.maxIPv6Networks = 5;
         this.enableDscp = false;
         this.enableCpuOveruseDetection = true;
         this.suspendBelowMinBitrate = false;
         this.screencastMinBitrate = null;
         this.networkPreference = PeerConnection.AdapterType.UNKNOWN;
         this.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
         this.activeResetSrtpParams = false;
         this.cryptoOptions = null;
         this.turnLoggingId = null;
         this.enableImplicitRollback = false;
         this.offerExtmapAllowMixed = true;
         this.enableIceGatheringOnAnyAddressPorts = false;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.IceTransportsType getIceTransportsType() {
         return this.iceTransportsType;
      }

      @CalledByNative("RTCConfiguration")
      List<PeerConnection.IceServer> getIceServers() {
         return this.iceServers;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.BundlePolicy getBundlePolicy() {
         return this.bundlePolicy;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.PortPrunePolicy getTurnPortPrunePolicy() {
         return this.turnPortPrunePolicy;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      RtcCertificatePem getCertificate() {
         return this.certificate;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.RtcpMuxPolicy getRtcpMuxPolicy() {
         return this.rtcpMuxPolicy;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.TcpCandidatePolicy getTcpCandidatePolicy() {
         return this.tcpCandidatePolicy;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.CandidateNetworkPolicy getCandidateNetworkPolicy() {
         return this.candidateNetworkPolicy;
      }

      @CalledByNative("RTCConfiguration")
      int getAudioJitterBufferMaxPackets() {
         return this.audioJitterBufferMaxPackets;
      }

      @CalledByNative("RTCConfiguration")
      boolean getAudioJitterBufferFastAccelerate() {
         return this.audioJitterBufferFastAccelerate;
      }

      @CalledByNative("RTCConfiguration")
      int getIceConnectionReceivingTimeout() {
         return this.iceConnectionReceivingTimeout;
      }

      @CalledByNative("RTCConfiguration")
      int getIceBackupCandidatePairPingInterval() {
         return this.iceBackupCandidatePairPingInterval;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.KeyType getKeyType() {
         return this.keyType;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.ContinualGatheringPolicy getContinualGatheringPolicy() {
         return this.continualGatheringPolicy;
      }

      @CalledByNative("RTCConfiguration")
      int getIceCandidatePoolSize() {
         return this.iceCandidatePoolSize;
      }

      @CalledByNative("RTCConfiguration")
      boolean getPruneTurnPorts() {
         return this.pruneTurnPorts;
      }

      @CalledByNative("RTCConfiguration")
      boolean getPresumeWritableWhenFullyRelayed() {
         return this.presumeWritableWhenFullyRelayed;
      }

      @CalledByNative("RTCConfiguration")
      boolean getSurfaceIceCandidatesOnIceTransportTypeChanged() {
         return this.surfaceIceCandidatesOnIceTransportTypeChanged;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getIceCheckIntervalStrongConnectivity() {
         return this.iceCheckIntervalStrongConnectivityMs;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getIceCheckIntervalWeakConnectivity() {
         return this.iceCheckIntervalWeakConnectivityMs;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getIceCheckMinInterval() {
         return this.iceCheckMinInterval;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getIceUnwritableTimeout() {
         return this.iceUnwritableTimeMs;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getIceUnwritableMinChecks() {
         return this.iceUnwritableMinChecks;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getStunCandidateKeepaliveInterval() {
         return this.stunCandidateKeepaliveIntervalMs;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getStableWritableConnectionPingIntervalMs() {
         return this.stableWritableConnectionPingIntervalMs;
      }

      @CalledByNative("RTCConfiguration")
      boolean getDisableIPv6OnWifi() {
         return this.disableIPv6OnWifi;
      }

      @CalledByNative("RTCConfiguration")
      int getMaxIPv6Networks() {
         return this.maxIPv6Networks;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      TurnCustomizer getTurnCustomizer() {
         return this.turnCustomizer;
      }

      @CalledByNative("RTCConfiguration")
      boolean getEnableDscp() {
         return this.enableDscp;
      }

      @CalledByNative("RTCConfiguration")
      boolean getEnableCpuOveruseDetection() {
         return this.enableCpuOveruseDetection;
      }

      @CalledByNative("RTCConfiguration")
      boolean getSuspendBelowMinBitrate() {
         return this.suspendBelowMinBitrate;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      Integer getScreencastMinBitrate() {
         return this.screencastMinBitrate;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.AdapterType getNetworkPreference() {
         return this.networkPreference;
      }

      @CalledByNative("RTCConfiguration")
      PeerConnection.SdpSemantics getSdpSemantics() {
         return this.sdpSemantics;
      }

      @CalledByNative("RTCConfiguration")
      boolean getActiveResetSrtpParams() {
         return this.activeResetSrtpParams;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      CryptoOptions getCryptoOptions() {
         return this.cryptoOptions;
      }

      @Nullable
      @CalledByNative("RTCConfiguration")
      String getTurnLoggingId() {
         return this.turnLoggingId;
      }

      @CalledByNative("RTCConfiguration")
      boolean getEnableImplicitRollback() {
         return this.enableImplicitRollback;
      }

      @CalledByNative("RTCConfiguration")
      boolean getOfferExtmapAllowMixed() {
         return this.offerExtmapAllowMixed;
      }

      @CalledByNative("RTCConfiguration")
      boolean getEnableIceGatheringOnAnyAddressPorts() {
         return this.enableIceGatheringOnAnyAddressPorts;
      }
   }

   public static enum SignalingState {
      STABLE,
      HAVE_LOCAL_OFFER,
      HAVE_LOCAL_PRANSWER,
      HAVE_REMOTE_OFFER,
      HAVE_REMOTE_PRANSWER,
      CLOSED;

      @CalledByNative("SignalingState")
      static PeerConnection.SignalingState fromNativeIndex(int nativeIndex) {
         return values()[nativeIndex];
      }

      // $FF: synthetic method
      private static PeerConnection.SignalingState[] $values() {
         return new PeerConnection.SignalingState[]{STABLE, HAVE_LOCAL_OFFER, HAVE_LOCAL_PRANSWER, HAVE_REMOTE_OFFER, HAVE_REMOTE_PRANSWER, CLOSED};
      }
   }

   public static enum IceConnectionState {
      NEW,
      CHECKING,
      CONNECTED,
      COMPLETED,
      FAILED,
      DISCONNECTED,
      CLOSED;

      @CalledByNative("IceConnectionState")
      static PeerConnection.IceConnectionState fromNativeIndex(int nativeIndex) {
         return values()[nativeIndex];
      }

      // $FF: synthetic method
      private static PeerConnection.IceConnectionState[] $values() {
         return new PeerConnection.IceConnectionState[]{NEW, CHECKING, CONNECTED, COMPLETED, FAILED, DISCONNECTED, CLOSED};
      }
   }

   public static enum PeerConnectionState {
      NEW,
      CONNECTING,
      CONNECTED,
      DISCONNECTED,
      FAILED,
      CLOSED;

      @CalledByNative("PeerConnectionState")
      static PeerConnection.PeerConnectionState fromNativeIndex(int nativeIndex) {
         return values()[nativeIndex];
      }

      // $FF: synthetic method
      private static PeerConnection.PeerConnectionState[] $values() {
         return new PeerConnection.PeerConnectionState[]{NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED};
      }
   }

   public static enum IceGatheringState {
      NEW,
      GATHERING,
      COMPLETE;

      @CalledByNative("IceGatheringState")
      static PeerConnection.IceGatheringState fromNativeIndex(int nativeIndex) {
         return values()[nativeIndex];
      }

      // $FF: synthetic method
      private static PeerConnection.IceGatheringState[] $values() {
         return new PeerConnection.IceGatheringState[]{NEW, GATHERING, COMPLETE};
      }
   }

   public interface Observer {
      @CalledByNative("Observer")
      void onSignalingChange(PeerConnection.SignalingState var1);

      @CalledByNative("Observer")
      void onIceConnectionChange(PeerConnection.IceConnectionState var1);

      @CalledByNative("Observer")
      default void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {
      }

      @CalledByNative("Observer")
      default void onConnectionChange(PeerConnection.PeerConnectionState newState) {
      }

      @CalledByNative("Observer")
      void onIceConnectionReceivingChange(boolean var1);

      @CalledByNative("Observer")
      void onIceGatheringChange(PeerConnection.IceGatheringState var1);

      @CalledByNative("Observer")
      void onIceCandidate(IceCandidate var1);

      @CalledByNative("Observer")
      default void onIceCandidateError(IceCandidateErrorEvent event) {
      }

      @CalledByNative("Observer")
      void onIceCandidatesRemoved(IceCandidate[] var1);

      @CalledByNative("Observer")
      default void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
      }

      @CalledByNative("Observer")
      void onAddStream(MediaStream var1);

      @CalledByNative("Observer")
      void onRemoveStream(MediaStream var1);

      @CalledByNative("Observer")
      void onDataChannel(DataChannel var1);

      @CalledByNative("Observer")
      void onRenegotiationNeeded();

      @CalledByNative("Observer")
      default void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
      }

      @CalledByNative("Observer")
      default void onRemoveTrack(RtpReceiver receiver) {
      }

      @CalledByNative("Observer")
      default void onTrack(RtpTransceiver transceiver) {
      }
   }

   public static enum SdpSemantics {
      /** @deprecated */
      @Deprecated
      PLAN_B,
      UNIFIED_PLAN;

      // $FF: synthetic method
      private static PeerConnection.SdpSemantics[] $values() {
         return new PeerConnection.SdpSemantics[]{PLAN_B, UNIFIED_PLAN};
      }
   }

   public static enum PortPrunePolicy {
      NO_PRUNE,
      PRUNE_BASED_ON_PRIORITY,
      KEEP_FIRST_READY;

      // $FF: synthetic method
      private static PeerConnection.PortPrunePolicy[] $values() {
         return new PeerConnection.PortPrunePolicy[]{NO_PRUNE, PRUNE_BASED_ON_PRIORITY, KEEP_FIRST_READY};
      }
   }

   public static enum ContinualGatheringPolicy {
      GATHER_ONCE,
      GATHER_CONTINUALLY;

      // $FF: synthetic method
      private static PeerConnection.ContinualGatheringPolicy[] $values() {
         return new PeerConnection.ContinualGatheringPolicy[]{GATHER_ONCE, GATHER_CONTINUALLY};
      }
   }

   public static enum KeyType {
      RSA,
      ECDSA;

      // $FF: synthetic method
      private static PeerConnection.KeyType[] $values() {
         return new PeerConnection.KeyType[]{RSA, ECDSA};
      }
   }

   public static enum AdapterType {
      UNKNOWN(0),
      ETHERNET(1),
      WIFI(2),
      CELLULAR(4),
      VPN(8),
      LOOPBACK(16),
      ADAPTER_TYPE_ANY(32),
      CELLULAR_2G(64),
      CELLULAR_3G(128),
      CELLULAR_4G(256),
      CELLULAR_5G(512);

      public final Integer bitMask;
      private static final Map<Integer, PeerConnection.AdapterType> BY_BITMASK = new HashMap();

      private AdapterType(Integer bitMask) {
         this.bitMask = bitMask;
      }

      @Nullable
      @CalledByNative("AdapterType")
      static PeerConnection.AdapterType fromNativeIndex(int nativeIndex) {
         return (PeerConnection.AdapterType)BY_BITMASK.get(nativeIndex);
      }

      // $FF: synthetic method
      private static PeerConnection.AdapterType[] $values() {
         return new PeerConnection.AdapterType[]{UNKNOWN, ETHERNET, WIFI, CELLULAR, VPN, LOOPBACK, ADAPTER_TYPE_ANY, CELLULAR_2G, CELLULAR_3G, CELLULAR_4G, CELLULAR_5G};
      }

      static {
         PeerConnection.AdapterType[] var0 = values();
         int var1 = var0.length;

         for(int var2 = 0; var2 < var1; ++var2) {
            PeerConnection.AdapterType t = var0[var2];
            BY_BITMASK.put(t.bitMask, t);
         }

      }
   }

   public static enum CandidateNetworkPolicy {
      ALL,
      LOW_COST;

      // $FF: synthetic method
      private static PeerConnection.CandidateNetworkPolicy[] $values() {
         return new PeerConnection.CandidateNetworkPolicy[]{ALL, LOW_COST};
      }
   }

   public static enum TcpCandidatePolicy {
      ENABLED,
      DISABLED;

      // $FF: synthetic method
      private static PeerConnection.TcpCandidatePolicy[] $values() {
         return new PeerConnection.TcpCandidatePolicy[]{ENABLED, DISABLED};
      }
   }

   public static enum RtcpMuxPolicy {
      NEGOTIATE,
      REQUIRE;

      // $FF: synthetic method
      private static PeerConnection.RtcpMuxPolicy[] $values() {
         return new PeerConnection.RtcpMuxPolicy[]{NEGOTIATE, REQUIRE};
      }
   }

   public static enum BundlePolicy {
      BALANCED,
      MAXBUNDLE,
      MAXCOMPAT;

      // $FF: synthetic method
      private static PeerConnection.BundlePolicy[] $values() {
         return new PeerConnection.BundlePolicy[]{BALANCED, MAXBUNDLE, MAXCOMPAT};
      }
   }

   public static enum IceTransportsType {
      NONE,
      RELAY,
      NOHOST,
      ALL;

      // $FF: synthetic method
      private static PeerConnection.IceTransportsType[] $values() {
         return new PeerConnection.IceTransportsType[]{NONE, RELAY, NOHOST, ALL};
      }
   }

   public static class IceServer {
      /** @deprecated */
      @Deprecated
      public final String uri;
      public final List<String> urls;
      public final String username;
      public final String password;
      public final PeerConnection.TlsCertPolicy tlsCertPolicy;
      public final String hostname;
      public final List<String> tlsAlpnProtocols;
      public final List<String> tlsEllipticCurves;

      /** @deprecated */
      @Deprecated
      public IceServer(String uri) {
         this(uri, "", "");
      }

      /** @deprecated */
      @Deprecated
      public IceServer(String uri, String username, String password) {
         this(uri, username, password, PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE);
      }

      /** @deprecated */
      @Deprecated
      public IceServer(String uri, String username, String password, PeerConnection.TlsCertPolicy tlsCertPolicy) {
         this(uri, username, password, tlsCertPolicy, "");
      }

      /** @deprecated */
      @Deprecated
      public IceServer(String uri, String username, String password, PeerConnection.TlsCertPolicy tlsCertPolicy, String hostname) {
         this(uri, Collections.singletonList(uri), username, password, tlsCertPolicy, hostname, (List)null, (List)null);
      }

      private IceServer(String uri, List<String> urls, String username, String password, PeerConnection.TlsCertPolicy tlsCertPolicy, String hostname, List<String> tlsAlpnProtocols, List<String> tlsEllipticCurves) {
         if (uri != null && urls != null && !urls.isEmpty()) {
            Iterator var9 = urls.iterator();

            String it;
            do {
               if (!var9.hasNext()) {
                  if (username == null) {
                     throw new IllegalArgumentException("username == null");
                  }

                  if (password == null) {
                     throw new IllegalArgumentException("password == null");
                  }

                  if (hostname == null) {
                     throw new IllegalArgumentException("hostname == null");
                  }

                  this.uri = uri;
                  this.urls = urls;
                  this.username = username;
                  this.password = password;
                  this.tlsCertPolicy = tlsCertPolicy;
                  this.hostname = hostname;
                  this.tlsAlpnProtocols = tlsAlpnProtocols;
                  this.tlsEllipticCurves = tlsEllipticCurves;
                  return;
               }

               it = (String)var9.next();
            } while(it != null);

            throw new IllegalArgumentException("urls element is null: " + urls);
         } else {
            throw new IllegalArgumentException("uri == null || urls == null || urls.isEmpty()");
         }
      }

      public String toString() {
         return this.urls + " [" + this.username + ":" + this.password + "] [" + this.tlsCertPolicy + "] [" + this.hostname + "] [" + this.tlsAlpnProtocols + "] [" + this.tlsEllipticCurves + "]";
      }

      public boolean equals(@Nullable Object obj) {
         if (obj == null) {
            return false;
         } else if (obj == this) {
            return true;
         } else if (!(obj instanceof PeerConnection.IceServer)) {
            return false;
         } else {
            PeerConnection.IceServer other = (PeerConnection.IceServer)obj;
            return this.uri.equals(other.uri) && this.urls.equals(other.urls) && this.username.equals(other.username) && this.password.equals(other.password) && this.tlsCertPolicy.equals(other.tlsCertPolicy) && this.hostname.equals(other.hostname) && this.tlsAlpnProtocols.equals(other.tlsAlpnProtocols) && this.tlsEllipticCurves.equals(other.tlsEllipticCurves);
         }
      }

      public int hashCode() {
         Object[] values = new Object[]{this.uri, this.urls, this.username, this.password, this.tlsCertPolicy, this.hostname, this.tlsAlpnProtocols, this.tlsEllipticCurves};
         return Arrays.hashCode(values);
      }

      public static PeerConnection.IceServer.Builder builder(String uri) {
         return new PeerConnection.IceServer.Builder(Collections.singletonList(uri));
      }

      public static PeerConnection.IceServer.Builder builder(List<String> urls) {
         return new PeerConnection.IceServer.Builder(urls);
      }

      @Nullable
      @CalledByNative("IceServer")
      List<String> getUrls() {
         return this.urls;
      }

      @Nullable
      @CalledByNative("IceServer")
      String getUsername() {
         return this.username;
      }

      @Nullable
      @CalledByNative("IceServer")
      String getPassword() {
         return this.password;
      }

      @CalledByNative("IceServer")
      PeerConnection.TlsCertPolicy getTlsCertPolicy() {
         return this.tlsCertPolicy;
      }

      @Nullable
      @CalledByNative("IceServer")
      String getHostname() {
         return this.hostname;
      }

      @CalledByNative("IceServer")
      List<String> getTlsAlpnProtocols() {
         return this.tlsAlpnProtocols;
      }

      @CalledByNative("IceServer")
      List<String> getTlsEllipticCurves() {
         return this.tlsEllipticCurves;
      }

      public static class Builder {
         @Nullable
         private final List<String> urls;
         private String username = "";
         private String password = "";
         private PeerConnection.TlsCertPolicy tlsCertPolicy;
         private String hostname;
         private List<String> tlsAlpnProtocols;
         private List<String> tlsEllipticCurves;

         private Builder(List<String> urls) {
            this.tlsCertPolicy = PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE;
            this.hostname = "";
            if (urls != null && !urls.isEmpty()) {
               this.urls = urls;
            } else {
               throw new IllegalArgumentException("urls == null || urls.isEmpty(): " + urls);
            }
         }

         public PeerConnection.IceServer.Builder setUsername(String username) {
            this.username = username;
            return this;
         }

         public PeerConnection.IceServer.Builder setPassword(String password) {
            this.password = password;
            return this;
         }

         public PeerConnection.IceServer.Builder setTlsCertPolicy(PeerConnection.TlsCertPolicy tlsCertPolicy) {
            this.tlsCertPolicy = tlsCertPolicy;
            return this;
         }

         public PeerConnection.IceServer.Builder setHostname(String hostname) {
            this.hostname = hostname;
            return this;
         }

         public PeerConnection.IceServer.Builder setTlsAlpnProtocols(List<String> tlsAlpnProtocols) {
            this.tlsAlpnProtocols = tlsAlpnProtocols;
            return this;
         }

         public PeerConnection.IceServer.Builder setTlsEllipticCurves(List<String> tlsEllipticCurves) {
            this.tlsEllipticCurves = tlsEllipticCurves;
            return this;
         }

         public PeerConnection.IceServer createIceServer() {
            return new PeerConnection.IceServer((String)this.urls.get(0), this.urls, this.username, this.password, this.tlsCertPolicy, this.hostname, this.tlsAlpnProtocols, this.tlsEllipticCurves);
         }
      }
   }

   public static enum TlsCertPolicy {
      TLS_CERT_POLICY_SECURE,
      TLS_CERT_POLICY_INSECURE_NO_CHECK;

      // $FF: synthetic method
      private static PeerConnection.TlsCertPolicy[] $values() {
         return new PeerConnection.TlsCertPolicy[]{TLS_CERT_POLICY_SECURE, TLS_CERT_POLICY_INSECURE_NO_CHECK};
      }
   }
}

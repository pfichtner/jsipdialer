package com.github.pfichtner.jsipdialer.nativeimage;

import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.Delete;

@Delete
@TargetClass(className = "org.mjsip.sip.provider.TlsTransport")
final class TlsTransportStub {}

@Delete
@TargetClass(className = "org.mjsip.sip.provider.TlsTransportConnection")
final class TlsTransportConnectionStub {}

@Delete
@TargetClass(className = "org.zoolu.net.TlsSocket")
final class TlsSocketStub {}

@Delete
@TargetClass(className = "org.zoolu.net.TlsSocketFactory")
final class TlsSocketFactoryStub {}

@Delete
@TargetClass(className = "org.zoolu.net.TlsServer")
final class TlsServerStub {}

@Delete
@TargetClass(className = "org.zoolu.net.TlsServerFactory")
final class TlsServerFactoryStub {}

@Delete
@TargetClass(className = "org.zoolu.net.TlsKeyTool")
final class TlsKeyToolStub {}

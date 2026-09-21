package com.lirxowo.curvyblocks.network;

import java.util.function.Consumer;

import com.lirxowo.curvyblocks.server.CurveServer;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CurveNetwork {
    private static volatile Consumer<CurvePayloads.Clientbound> clientReceiver;

    private CurveNetwork() {
    }

    public static void setClientReceiver(Consumer<CurvePayloads.Clientbound> receiver) {
        clientReceiver = receiver;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(CurvePayloads.Place.TYPE, CurvePayloads.Place.CODEC, CurveServer::place);
        registrar.playToServer(CurvePayloads.Remove.TYPE, CurvePayloads.Remove.CODEC, CurveServer::remove);
        registrar.playToServer(CurvePayloads.Mode.TYPE, CurvePayloads.Mode.CODEC, CurveServer::mode);
        registrar.playToClient(CurvePayloads.ChunkSync.TYPE, CurvePayloads.ChunkSync.CODEC, CurveNetwork::receive);
        registrar.playToClient(CurvePayloads.ForgetChunk.TYPE, CurvePayloads.ForgetChunk.CODEC, CurveNetwork::receive);
        registrar.playToClient(CurvePayloads.Added.TYPE, CurvePayloads.Added.CODEC, CurveNetwork::receive);
        registrar.playToClient(CurvePayloads.Removed.TYPE, CurvePayloads.Removed.CODEC, CurveNetwork::receive);
        registrar.playToClient(CurvePayloads.Response.TYPE, CurvePayloads.Response.CODEC, CurveNetwork::receive);
    }

    private static void receive(CurvePayloads.Clientbound payload, IPayloadContext context) {
        if (clientReceiver != null) {
            clientReceiver.accept(payload);
        }
    }
}

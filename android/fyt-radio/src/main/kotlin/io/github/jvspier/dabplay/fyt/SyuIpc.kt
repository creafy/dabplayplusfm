package io.github.jvspier.dabplay.fyt

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * The FYT MainServer's Binder interface (`com.syu.ms`, service `app.ToolkitService`), written out by hand from the
 * wire format its own apps use (../docs/fyt-radio.md): a toolkit hands out one module per code, a module takes commands
 * (`cmd`), answers queries (`get`) and calls registered callbacks with numbered updates. The descriptors must match
 * the server's exactly. `cmd`, `register` and `unregister` are one-way calls, as in the original apps.
 */
internal object Syu {
    const val PACKAGE = "com.syu.ms"
    const val SERVICE = "app.ToolkitService"
    const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    const val MODULE = "com.syu.ipc.IRemoteModule"
    const val CALLBACK = "com.syu.ipc.IModuleCallback"

    /** Module codes: 0 main (audio source), 1 radio. */
    const val MODULE_MAIN = 0
    const val MODULE_RADIO = 1
}

/** What a module's `get` answers. */
class ModuleValue(val ints: IntArray?, val flts: FloatArray?, val strs: Array<String>?)

internal class RemoteToolkit(private val binder: IBinder) {
    fun module(code: Int): RemoteModule? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Syu.TOOLKIT)
            data.writeInt(code)
            binder.transact(1, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()?.let(::RemoteModule)
        } finally { reply.recycle(); data.recycle() }
    }
}

internal class RemoteModule(val binder: IBinder) {
    fun cmd(code: Int, ints: IntArray? = null, strs: Array<String>? = null) = oneway(1) {
        writeInt(code); writeIntArray(ints); writeFloatArray(null); writeStringArray(strs)
    }

    fun get(code: Int, ints: IntArray? = null): ModuleValue? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Syu.MODULE)
            data.writeInt(code); data.writeIntArray(ints); data.writeFloatArray(null); data.writeStringArray(null)
            binder.transact(2, data, reply, 0)
            reply.readException()
            return if (reply.readInt() != 0) ModuleValue(reply.createIntArray(), reply.createFloatArray(), reply.createStringArray()) else null
        } finally { reply.recycle(); data.recycle() }
    }

    /** [flag] 1 is what the FYT apps send (the server then also sends the current value). */
    fun register(cb: ModuleCallback, update: Int, flag: Int = 1) = oneway(3) { writeStrongBinder(cb); writeInt(update); writeInt(flag) }
    fun unregister(cb: ModuleCallback, update: Int) = oneway(4) { writeStrongBinder(cb); writeInt(update) }

    private inline fun oneway(code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(Syu.MODULE)
            data.write()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } finally { data.recycle() }
    }
}

/** The callback the server calls with `update(code, ints, flts, strs)`, on a Binder thread. */
internal class ModuleCallback(private val onUpdate: (Int, IntArray?, Array<String>?) -> Unit) : Binder() {
    init { attachInterface(null, Syu.CALLBACK) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != 1) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(Syu.CALLBACK)
        val update = data.readInt()
        val ints = data.createIntArray()
        data.createFloatArray()
        val strs = data.createStringArray()
        onUpdate(update, ints, strs)
        return true
    }
}

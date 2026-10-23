package com.delminiusapps.tillfailure.identity

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.ViewModelStoreNavEntryDecorator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.reflect.KClass
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationStoresTest {
    @BeforeTest fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun reset() { Dispatchers.resetMain() }
    private class Owner : ViewModelStoreOwner, HasDefaultViewModelProviderFactory, LifecycleOwner {
        override val viewModelStore = ViewModelStore()
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        // Destination owners may allocate a new extras instance on every access.
        private val ownerExtra = object : CreationExtras.Key<Any> {}
        override val defaultViewModelCreationExtras: CreationExtras get() = MutableCreationExtras().apply { this[ownerExtra] = Any() }
        override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = error("Unused factory")
        }
    }
    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
    @Test fun ledgerPresentationRecompositionKeepsDecoratorButNewAccountOwnerGetsNewStore() = runTest {
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frames)
        val runner = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val a = Owner(); val b = Owner()
        var owner by mutableStateOf(a)
        var pending by mutableStateOf(false)
        val seen = mutableListOf<ViewModelStoreNavEntryDecorator<String>>()
        composition.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner) {
                val decorator = rememberProductViewModelStoreDecorator<String>()
                val commandPending = pending
                SideEffect { seen += decorator; commandPending.hashCode() }
            }
        }
        runCurrent()
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); frames.sendFrame(1L); runCurrent() }
        val initial = seen.last()
        pending = true; frame(); assertEquals(2, seen.size)
        assertSame(initial, seen.last(), "Persisting a protected command must not dispose its issuing destination")
        pending = false; frame(); assertEquals(3, seen.size)
        assertSame(initial, seen.last(), "Durable settlement must not replace the destination before its callback")
        owner = b; frame(); assertEquals(4, seen.size)
        assertNotSame(initial, seen.last(), "Account/root owners must remain isolated")
        composition.dispose(); recomposer.close(); runner.cancel()
        a.viewModelStore.clear(); b.viewModelStore.clear()
    }
}

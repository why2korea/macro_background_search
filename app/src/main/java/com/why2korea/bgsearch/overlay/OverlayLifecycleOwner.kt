package com.why2korea.bgsearch.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * WindowManager 로 직접 붙이는 뷰에는 Activity 가 없으므로
 * ComposeView 가 요구하는 LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner 를
 * 서비스가 대신 제공한다. 이걸 붙이지 않으면 ComposeView 는
 * "ViewTreeLifecycleOwner not found" 로 즉시 예외를 던진다.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** 윈도우에 붙일 루트 뷰에 소유자들을 심는다. addView 전에 호출해야 한다. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

import com.google.r4a.*

class ClassComponent(
    @Children var children: (x: Int) -> Unit
): Component() {
    override fun compose() {}
}

fun f(){
    <<caret>
}

// EXIST: { lookupString: "ClassComponent", itemText: "<ClassComponent> x -> ... </ClassComponent>" }
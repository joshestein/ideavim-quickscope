package com.joshestein.ideavimquickscope

class IdeaVimQuickscopeExtension {
class IdeaVimQuickscopeExtension : VimExtension {

    override fun getName() = "quickscope";
    override fun init() {
        // TODO: NVO?
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-forward-find"), owner, QuickscopeHandler("f"), false);
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-forward-to"), owner, QuickscopeHandler("t"), false);
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-backward-find"), owner, QuickscopeHandler("F"), false);
        putExtensionHandlerMapping(MappingMode.NXO, parseKeys("<Plug>quickscope-backward-to"), owner, QuickscopeHandler("T"), false);

        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("f"), owner, parseKeys("<Plug>quickscope-forward-find"), true);
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("t"), owner, parseKeys("<Plug>quickscope-forward-to"), true);
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("F"), owner, parseKeys("<Plug>quickscope-backward-find"), true);
        putKeyMappingIfMissing(MappingMode.NXO, parseKeys("T"), owner, parseKeys("<Plug>quickscope-backward-to"), true);
    }
}
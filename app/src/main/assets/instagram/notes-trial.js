// Executed only in the visible Instagram inbox. No native bridge or network requests.
(function notesTrial(step, handle, title, artist) {
    if (location.origin !== "https://www.instagram.com" || location.pathname !== "/direct/inbox/" ||
        document.visibilityState !== "visible" || document.querySelector('input[type=password]')) return "unavailable";

    const norm = value => (value || "").normalize("NFKC").trim().replace(/\s+/g, " ").toLowerCase();
    const visible = el => el && el.getClientRects().length && getComputedStyle(el).visibility === "visible";
    const all = (root, selector) => Array.from(root.querySelectorAll(selector)).filter(visible);
    const unique = list => list.length === 1 ? list[0] : null;
    const label = el => norm(el.getAttribute("aria-label") || el.innerText);
    const buttons = root => all(root, 'button,[role=button]').filter(el =>
        !el.disabled && el.getAttribute("aria-disabled") !== "true");
    const button = (root, names) => unique(buttons(root).filter(el => names.includes(label(el))));
    const click = (el, next) => { if (!el) return "unavailable"; el.click(); return next; };

    // Check a profile navigation control, never a profile link inside a message or a Note.
    const profile = all(document, 'nav a[href], [role=navigation] a[href]').some(link => {
        if (link.origin !== location.origin || link.pathname.toLowerCase() !== "/" + handle + "/") return false;
        return [link, ...link.querySelectorAll("[aria-label],img[alt]")].some(el =>
            ["profile", "profil", handle + "'s profile picture", handle + " profil resmi"].includes(
                norm(el.getAttribute("aria-label") || el.getAttribute("alt") || el.innerText)));
    });
    if (!profile) return "account";
    const newNote = ["leave a note", "new note", "create a note", "not bırak", "yeni not", "not oluştur"];
    const noteHeadings = [...newNote, "share a note", "not paylaş"];
    const musicHeadings = ["music", "müzik", "search music", "müzik ara", "add music", "müzik ekle"];
    const dialogs = all(document, '[role=dialog],dialog[open]');
    const dialog = unique(dialogs);
    function namedDialog(names) {
        return dialog && (names.includes(norm(dialog.getAttribute("aria-label"))) ||
            all(dialog, 'h1,h2,h3,[role=heading]').some(h => names.includes(label(h))));
    }
    if (step === "open") {
        if (dialogs.length) return "unavailable";
        return click(button(document, newNote), "music");
    }
    if (step === "music" && namedDialog(noteHeadings))
        return click(button(dialog, musicHeadings), "search");
    if (step === "search" && namedDialog(musicHeadings)) {
        const box = unique(all(dialog, 'input[type=search],input[type=text],input:not([type])').filter(el =>
            !el.readOnly && !el.disabled && ["search", "ara", ...musicHeadings].includes(
                norm(el.getAttribute("aria-label") || el.placeholder))));
        if (!box) return "unavailable";
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
        setter.call(box, title + " " + artist);
        box.dispatchEvent(new Event("input", {bubbles: true}));
        return "select";
    }
    // Exact, separate title and artist labels in one result. Keep version/remix text.
    function hasRecording(root) {
        const lines = (root.innerText || "").split(/\n/).map(norm).filter(Boolean);
        return norm(title) !== norm(artist) && lines.includes(norm(title)) && lines.includes(norm(artist));
    }
    if (step === "select" && namedDialog(musicHeadings)) {
        const rows = all(dialog, 'button,[role=button],[role=option]').filter(hasRecording);
        return click(unique(rows), "review");
    }
    if (step === "review" && namedDialog(musicHeadings) && hasRecording(dialog))
        return click(button(dialog, ["done", "bitti", "tamam"]), "composer");
    if ((step === "composer" || step === "review" || step === "submit") && namedDialog(noteHeadings)) {
        // A recording shown within the composer after selecting from the music catalog.
        const clip = all(dialog, "audio").length > 0 || buttons(dialog).some(el =>
            ["play music", "play clip", "remove music", "edit music", "müziği çal",
                "kesiti çal", "müziği kaldır", "müziği düzenle"].includes(label(el)));
        if (!clip || !hasRecording(dialog)) return "recording";
        const selected = button(dialog, ["audience: close friends", "hedef kitle: yakın arkadaşlar",
            "share with: close friends", "sharing with close friends"]);
        const choice = button(dialog, ["close friends", "yakın arkadaşlar"]);
        if (!selected && !(choice && (choice.getAttribute("aria-pressed") === "true" ||
            choice.getAttribute("aria-checked") === "true"))) return "audience";
        const share = button(dialog, ["share", "paylaş"]);
        if (!share) return "unavailable";
        return step === "submit" ? click(share, "sent") : "ready";
    }
    return "unavailable";
})

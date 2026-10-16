// Preserve links to topics that moved from the original one-page guide.
function openLinkedGuide() {
  const topic = document.getElementById(location.hash.slice(1));
  if (topic instanceof HTMLAnchorElement && topic.hasAttribute("data-guide")) {
    location.replace(topic.href);
  }
}
openLinkedGuide();
window.addEventListener("hashchange", openLinkedGuide);

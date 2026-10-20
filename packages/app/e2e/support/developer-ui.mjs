/** @param {import('@playwright/test').Page} page */
export async function hideDeveloperToasts(page) {
  // Keep developer chrome out of app interactions without Playwright locator
  // handlers: parallel app assertions can race while dismissing the same toast.
  // Console errors remain in the runner's logs.
  function hideToasts() {
    const installStyle = () => {
      const style = document.createElement("style");
      style.textContent = "#error-toast { display: none !important; }";
      document.head.appendChild(style);
    };
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", installStyle, { once: true });
    } else {
      installStyle();
    }
  }
  await page.addInitScript(hideToasts);
  // Electron connects to an already loaded page; apply there as well.
  await page.evaluate(hideToasts);
}

/*
 * Markdown renders a fenced block as <pre><code class="language-x">. The skin's SyntaxHighlighter
 * reads the <pre>'s HTML as source text, so the <code> tag shows up inside the snippet, and gets
 * copied with it. Unwrap the <code> and turn its language into a brush the skin ships.
 *
 * This runs on DOM ready. The skin only queues SyntaxHighlighter for window load, so this always
 * gets there first.
 */
(function () {
  var brushes = { xml: 'xml', html: 'xml', bash: 'bash', shell: 'bash', java: 'java', js: 'js' };

  document.addEventListener('DOMContentLoaded', function () {
    var blocks = document.querySelectorAll('div.source > pre > code');
    for (var i = 0; i < blocks.length; i++) {
      var code = blocks[i];
      var pre = code.parentNode;
      var match = /language-(\w+)/.exec(code.className);
      pre.className = 'brush: ' + ((match && brushes[match[1]]) || 'plain');
      pre.textContent = code.textContent;
    }
  });
})();

/*
 * The MIT License
 *
 * Copyright (c) 2021, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
function saveApiToken(button) {
  if (button.classList.contains('request-pending')) {
    return;
  }
  button.classList.add('request-pending');
  const repeatedChunk = button.closest('.repeated-chunk');

  const targetUrl = button.getAttribute('data-target-url');
  fetch(targetUrl, {
    method: 'post',
    headers: crumb.wrap({}),
  }).then((resp) => {
    if (resp.ok) {
      resp.json().then((json) => {
        if (json.status === 'error') {
          button.classList.remove('request-pending');
        } else {
          const {tokenValue} = json.data;
          // The visible part, so it can be copied
          const tokenValueSpan = repeatedChunk.querySelector('span.metrics-access-key-new-token-value');
          tokenValueSpan.innerText = tokenValue;
          tokenValueSpan.classList.add('visible');

          // The input sent to save the configuration
          repeatedChunk.querySelector('[name = "key"]').value = tokenValue;

          // Show the copy button
          const tokenCopyButton = repeatedChunk.querySelector('.copy-button');
          tokenCopyButton.setAttribute('text', tokenValue);
          tokenCopyButton.classList.remove('invisible')
          tokenCopyButton.classList.add('visible');

          // Show the warning message
          repeatedChunk.querySelector('.metrics-access-key-display-after-generation').classList.add('visible');

          button.remove();
        }
      });
    }
  });
}

Behaviour.specify(".metrics-generate-api-token", "MetricsAccessKey_generateToken", 0, (element) => {
  element.addEventListener("click", (event) => {
    saveApiToken(event.target);
  });
});

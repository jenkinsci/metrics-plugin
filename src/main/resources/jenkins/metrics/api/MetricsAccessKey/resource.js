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
  if (button.hasClassName('request-pending')) {
    return;
  }
  button.addClassName('request-pending');
  const repeatedChunk = button.up('.repeated-chunk');

  const targetUrl = button.getAttribute('data-target-url');
  new Ajax.Request(targetUrl, {
    method: 'post',
    onSuccess: function (resp) {
      const json = resp.responseJSON;
      if (json.status === 'error') {
        button.removeClassName('request-pending');
      } else {
        const {tokenValue} = json.data;
        // The visible part, so it can be copied
        const tokenValueSpan = repeatedChunk.querySelector('span.metrics-access-key-new-token-value');
        tokenValueSpan.innerText = tokenValue;
        tokenValueSpan.addClassName('visible');

        // The input sent to save the configuration
        repeatedChunk.querySelector('[name = "key"]').value = tokenValue;

        // Show the copy button
        const tokenCopyButton = repeatedChunk.querySelector('.copy-button');
        tokenCopyButton.setAttribute('text', tokenValue);
        tokenCopyButton.removeClassName('invisible')
        tokenCopyButton.addClassName('visible');

        // Show the warning message
        repeatedChunk.querySelector('.metrics-access-key-display-after-generation')
            .addClassName('visible');

        button.remove();
      }
    },
  });
}

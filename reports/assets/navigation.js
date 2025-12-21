document.addEventListener('DOMContentLoaded', function() {
    const items = document.querySelectorAll('.sidebar li');
    const frame = document.getElementById('report-frame');

    items.forEach(item => {
        item.addEventListener('click', function() {
            items.forEach(i => i.classList.remove('active'));
            this.classList.add('active');
            const component = this.dataset.component;
            frame.src = component + '/index.html';
        });
    });

    // Set first item as active
    if (items.length > 0) {
        items[0].classList.add('active');
    }
});
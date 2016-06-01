/**
 * 
 */
angular.module('monitorTypeListView', [])
.controller('monitorTypeListCtrl',
[ '$timeout',
  '$scope',
  '$http',
  function($timeout, $scope, $http){
	var timer;
	var local_url = '/assets/localization/zh_tw.json';
	
	var local_url = '/assets/localization/zh_tw.json';
	
	var oTable = $("#mtTable").dataTable( {
		ajax: {
	        url: '/MonitorTypeStatusList',
	        dataSrc: ''
	    },
		columns: [
		          { data: {
		        	  "_":'order',
		        	  "display":"desp"
		        		  } },
		          { data: 'value' },
		          { data: 'unit' },
		          { data: 'instrument' },
		          { data: 'status' }
		      ],
		language: {
			url: local_url,							
		},
		rowCallback: function( row, data, index ) {
		    $(row).addClass(data.classStr);
		  },
		paging:   false,
		info: false,
		searching: false,
		responsive: true
	} );

	function reload(){
		oTable.api().ajax.reload();
		timer = $timeout(reload, 3000);		
	};

	timer = $timeout(reload, 3000);
	
    $scope.$on("$destroy", function() {
        if (timer) {
            $timeout.cancel(timer);
        }
    });
}]);
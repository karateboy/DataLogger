angular.module('mtRealtimeChartView2', [])
.controller('mtRealtimeChartCtrl2',
[ '$timeout',
  '$scope',
  '$http',
  function($timeout, $scope, $http){
	var self = this;
	var chartSeries = [];
	var timer;
	var updateFeq = 3000;
	function load() {
		//This is not controller...
		var series = this.series;
        function getLatestData(){
            $http.get('/MonitorTypeStatusList').then(function(response) {
    			var result = response.data;
    			var x = new Date().getTime();
    			for(var i=0;i<result.length;i++){
        			for(var j=0;j<series.length;j++){
            			if(series[j].name == result[i].desp){
                			var y = parseFloat(result[i].value);
            				series[j].addPoint([x, y], true, true);
            				break;
            			}
        			}                    							
    			}
            });
            timer = $timeout(getLatestData, updateFeq);
        }        
        timer = $timeout(getLatestData, updateFeq);
    }
	
	function init(){
		$http.get('/MonitorTypeStatusList').then(function(response) {
			var result = response.data;
			var now = new Date().getTime();
			for(var i=0;i<result.length;i++){
				var data =[];
				for(j=0;j<20;j++)
					data.push({
		            	x:now,
		            	y:parseFloat(result[i].value)});
            	
				var series = 
					{
			            name: result[i].desp,
			            visible:i==1,
			            type: 'spline',
			            data: data
			        }
				chartSeries.push(series);			
			}
			//Init chart
		    $('#mtRealtimeChart2').highcharts({
		        chart: {
		            type: 'spline',
		            marginRight: 10,
		            height: 300,		            
		            events: {
		                load: load
		            }		            
		        },
				navigation:{
			    	buttonOptions: {
			        	enabled: true
			    	}
			    },
			    credits :{
					enabled : false,
				},
		        
		        title: {
		            text: '測項即時曲線圖'
		        },
		        xAxis: {
		            type: 'datetime',
		            tickPixelInterval: 150
		        },
		        yAxis: {
		            title: {
		                text: 'value'
		            },
		            plotLines: [{
		                value: 0,
		                width: 1,
		                color: '#808080'
		            }]
		        },
		        tooltip: {
		            formatter: function () {
		                return '<b>' + this.series.name + '</b><br/>' +
		                    Highcharts.dateFormat('%Y-%m-%d %H:%M:%S', this.x) + '<br/>' +
		                    Highcharts.numberFormat(this.y, 2);
		            }
		        },
		        exporting: {
		            enabled: false
		        },
		        series: chartSeries
		    });
		}, function(errResponse) {
			console.error('Error while fetching notes');
		});			
	}
	
    $scope.$on("$destroy", function() {
        if (timer) {
            $timeout.cancel(timer);
        }
    });
    
	init();
  }]);
